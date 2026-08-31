package com.aengine.persistence.db;

import com.aengine.persistence.AbstractEntity;
import com.aengine.persistence.ColumnMeta;
import com.aengine.persistence.IndexMeta;
import com.aengine.persistence.TableMeta;
import com.aengine.persistence.annotation.CRepository;
import com.aengine.persistence.annotation.Table;
import com.aengine.util.DateUtil;
import com.aengine.util.concurrent.WrappedRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 数据表滚动生成 目前已知的问题：无法自动修复表结构
 *
 *
 */
public class RollingJDBCRepository<T extends AbstractEntity> extends JDBCRepository<T> {

	private static Logger log = LoggerFactory.getLogger(RollingJDBCRepository.class);

	protected List<Map<String, Object>> rollTableMap = new ArrayList<>();// 缓存一份自身的管理表数据

	protected ConcurrentHashMap<String, List<T>> cacheList = new ConcurrentHashMap<>();

	private static final String ROLL_TYPE_SUFFIX = "_roll_table";

	private volatile String tableName = null;

	private int batch = 0;// 定量存储

	private int delay = 0;// 定时存储
	
	private long currentTime = 0L;// 表名更换时间点

	private long nextSaveTime = 0L;// 下一次定时存储时间点
	
	private long nextFixTableTime = 0L;// 下一次检查预建表时间点

	public enum RollingType {
		NONE, DAILY, WEEKLY, MONTHLY,;
	}

	private class DelayTask extends WrappedRunnable {
		public void execute() {
			if (nextSaveTime <= System.currentTimeMillis())
				batchSave();
			dayCheck();// 根据时间，每日检查对应的日志内的表是否有建立
		}
	}

	private final RollingType rollingType;

	public RollingJDBCRepository() {
		super();
		CRepository repository = getAnnotation();
		this.rollingType = RollingType.valueOf(repository.rolling().toUpperCase());
		this.batch = repository.batch();
		this.delay = repository.delay();
		try {
			this.fixTable();
		} catch (Exception e) {
			log.error("fix table failed", e);
		}
		
		this.pool.scheduleWithFixedDelay(new DelayTask(), 1, 1, TimeUnit.SECONDS);// 定时存储与检查
		DBManager.getInstance().addHook(() -> batchSave());// 关闭前存储
	}

	/**
	 * 获取数据库对象
	 *
	 * @return
	 */
	public DB getDB() {
		return db;
	}

	/**
	 * 定时检查预建表
	 */
	private void dayCheck() {
		if (nextFixTableTime == 0) {
			Calendar calendar = Calendar.getInstance();
			if (calendar.get(Calendar.HOUR_OF_DAY) >= 23 && calendar.get(Calendar.MINUTE) >= 58)//大于23点58分时，将时间推向明天
				calendar.add(Calendar.DAY_OF_YEAR, 1);
			calendar.set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_YEAR), 23, 58, 0);
			calendar.set(Calendar.MILLISECOND, 0);
			nextFixTableTime = calendar.getTimeInMillis();
		}
		// 如果每天零点检查数据是否预建，可能会影响当天的零点的数据存储，改为23点58分检查？
		if (nextFixTableTime <= System.currentTimeMillis()) {
			try {
				this.fixTable();
			} catch (Exception e) {
				log.error("fix table failed", e);
			}
			nextFixTableTime += DateUtil.ONE_DAY_MILLISECOND;
		}
		
	}

	@Override
	public void add(T entity) {
		cacheList.putIfAbsent(tabName(), new ArrayList<>());
		cacheList.get(tabName()).add(entity);
		checkSaveLimit();
	}

	@Override
	public void add(List<T> entities) {
		cacheList.putIfAbsent(tabName(), new ArrayList<>());
		cacheList.get(tabName()).addAll(entities);
		checkSaveLimit();
	}

	private void checkSaveLimit() {
		int count = 0;
		for (List<T> list : cacheList.values()) {
			count += list.size();
		}

		if (count >= batch)
			batchSave();
	}

	private synchronized void batchSave() {
		this.nextSaveTime = System.currentTimeMillis() + delay * 1000;// 刷新下次定时存储时间
		if (cacheList == null || cacheList.size() == 0) {
			return;
		}
		for (String tableName : cacheList.keySet()) {
			String sql = buildInsertSQL(tableName);
			if (log.isDebugEnabled()) {
				log.debug(sql);
			}
			Connection connection = null;
			PreparedStatement ps = null;
			try {
				connection = db.getConnection();
				connection.setAutoCommit(false);
				ps = connection.prepareStatement(sql);
				for (T e : cacheList.get(tableName)) {
					prepareStatementInsert(ps, e);
					ps.addBatch();
				}
				ps.executeBatch();
				connection.commit();
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			} finally {
				if (ps != null) {
					try {
						ps.close();
					} catch (SQLException e) {
						log.error("close statement failed", e);
					}
				}
				if (connection != null) {
					try {
						connection.setAutoCommit(true);
						connection.close();
					} catch (SQLException e) {
						log.error("close connection failed", e);
					}
				}
			}
		}
		cacheList.clear();
	}

	// 删除指定时间内符合条件的数据 按照日期字符串来比较 格式固定位 yyyyMMdd
	public void remove(String field, Object value, String start, String end) {
		List<String> tableNames = new ArrayList<>();
		int startTime = Integer.valueOf(start);
		int endTime = Integer.valueOf(end);
		if (startTime < endTime) {// 时间排序
			int temp = startTime;
			startTime = endTime;
			endTime = temp;
		}
		for (Map<String, Object> tableHead : rollTableMap) {// 开始时间大于endSaveTime 或者 结束时间小于 startSaveTime
			int startSaveTime = Integer.valueOf(tableHead.get("startSaveTime").toString());
			int endSaveTime = Integer.valueOf(tableHead.get("endSaveTime").toString());
			if (endTime < startSaveTime || startTime > endSaveTime)
				continue;
			else {
				tableNames.add(tableHead.get("tableName").toString());
			}
		}

		if (tableNames.isEmpty())
			return;

		Map<String, Object> options = null;
		if (field != null && value != null) {
			options = new HashMap<>();
			options.put(field, value);
		}

		for (String tableName : tableNames) {// 删除数据
			List<ColumnMeta> cols = new ArrayList<>();
			List<Object> values = new ArrayList<>();
			String sql = buildDeleteSQL(options, tableName, cols, values);
			try {
				// DELETE 必须走 executeUpdate；db.query 用 executeQuery 执行 DELETE 会抛异常导致删除失效
				db.update(sql, ps -> {
					for (int i = 0; i < cols.size(); i++) {
						bindValue(ps, i + 1, cols.get(i), values.get(i));
					}
				});
			} catch (SQLException e) {
				log.error("delete Date Error By sql : " + sql, e);
			}
		}

	}

	// 删除指定时间内的所有数据 按照日期字符串来比较 格式固定位 yyyyMMdd
	public void removeAll(String start, String end) {
		remove(null, null, start, end);
	}

	// 获取指定时间内符合条件的一条数据
	public T get(String field, Object value, String start, String end) {
		List<T> result = list(field, value, start, end);
		if (result == null || result.isEmpty())
			return null;
		return result.get(0);
	}

	// 获取指定时间内的所有数据
	public List<T> listAll(String startTime, String endTime) {
		return list(null, null, startTime, endTime);
	}

	// 获取指定时间内符合条件的所有数据
	public List<T> list(String field, Object value, String start, String end) {
		List<String> tableNames = new ArrayList<>();
		int startTime = Integer.valueOf(start);
		int endTime = Integer.valueOf(end);
		if (startTime < endTime) {// 时间排序
			int temp = startTime;
			startTime = endTime;
			endTime = temp;
		}
		for (Map<String, Object> tableHead : rollTableMap) {// 开始时间大于endSaveTime 或者 结束时间小于 startSaveTime
			int startSaveTime = Integer.valueOf(tableHead.get("startSaveTime").toString());
			int endSaveTime = Integer.valueOf(tableHead.get("endSaveTime").toString());
			if (endTime < startSaveTime || startTime > endSaveTime)
				continue;
			else {
				tableNames.add(tableHead.get("tableName").toString());
			}
		}

		if (tableNames.isEmpty())
			return null;

		Map<String, Object> options = null;
		if (field != null && value != null) {
			options = new HashMap<>();
			options.put(field, value);
		}

		List<T> list = new ArrayList<>();
		for (String tableName : tableNames) {// 拉去各个表数据
			List<ColumnMeta> cols = new ArrayList<>();
			List<Object> values = new ArrayList<>();
			String sql = buildSelectSQL(tableName, options, cols, values);

			try {
				List<Map<String, Object>> result = db.query(sql, ps -> {
					for (int i = 0; i < cols.size(); i++) {
						bindValue(ps, i + 1, cols.get(i), values.get(i));
					}
				});
				if (result != null && !result.isEmpty())
					list.addAll(parse(meta, result));
			} catch (SQLException e) {
				log.error("get Date Error By sql : " + sql, e);
			}
		}

		return list;

	}

	/**
	 * SQL装载
	 * 
	 * @param ps
	 * @param entity
	 * @throws Exception
	 */
	protected void prepareStatementInsert(PreparedStatement ps, T entity) throws Exception {
		int index = 1;
		for (int i = 0; i < meta.getColumns().size(); i++) {
			ColumnMeta col = meta.getColumns().get(i);
			if (!col.isPk() || !col.isAuto()) {
				prepareStatement(ps, index++, col, entity);
			}
		}
	}

	/**
	 * 插入SQL
	 * 
	 * @param
	 * @return
	 */
	protected String buildInsertSQL() {
		StringBuilder sb = new StringBuilder();
		sb.append("INSERT INTO `");
		sb.append(tabName());
		sb.append("` (");
		StringBuilder vTemp = new StringBuilder();
		StringBuilder cTemp = new StringBuilder();
		for (int i = 0; i < meta.getColumns().size(); i++) {
			ColumnMeta col = meta.getColumns().get(i);
			if (!col.isPk() || !col.isAuto()) {
				cTemp.append("`").append(col.getName()).append("`, ");
				vTemp.append("?, ");
			}
		}
		sb.append(cTemp.subSequence(0, cTemp.length() - 2));
		sb.append(") VALUE (").append(vTemp.subSequence(0, vTemp.length() - 2)).append(")");
		return sb.toString();
	}

	/**
	 * 插入SQL
	 * 
	 * @param tableName
	 * @return
	 */
	protected String buildInsertSQL(String tableName) {
		StringBuilder sb = new StringBuilder();
		sb.append("INSERT INTO `");
		sb.append(tableName);
		sb.append("` (");
		StringBuilder vTemp = new StringBuilder();
		StringBuilder cTemp = new StringBuilder();
		for (int i = 0; i < meta.getColumns().size(); i++) {
			ColumnMeta col = meta.getColumns().get(i);
			if (!col.isPk() || !col.isAuto()) {
				cTemp.append("`").append(col.getName()).append("`, ");
				vTemp.append("?, ");
			}
		}
		sb.append(cTemp.subSequence(0, cTemp.length() - 2));
		sb.append(") VALUE (").append(vTemp.subSequence(0, vTemp.length() - 2)).append(")");
		return sb.toString();
	}

	/**
	 * 删除SQL 删除一定时间内的日志，或者删除一定时间内指定条件的日志
	 * 
	 * @param options
	 * @param tableName
	 * @return
	 */
	protected String buildDeleteSQL(Map<String, Object> options, String tableName, List<ColumnMeta> outCols, List<Object> outValues) {
		if (options != null && !options.isEmpty())
			return "DELETE FROM `" + tableName + "` " + buildWhereSQL(options, outCols, outValues);
		return "DELETE FROM `" + tableName + "` WHERE 1=1";// 删除表里所有数据

	}

	/**
	 * 查询SQL
	 * 
	 * @param tableName
	 * @param option
	 * @return
	 */
	protected String buildSelectSQL(String tableName, Map<String, Object> option, List<ColumnMeta> outCols, List<Object> outValues) {
		StringBuilder sb = new StringBuilder();
		sb.append("SELECT ");
		for (int i = 0; i < meta.getColumns().size(); i++) {
			ColumnMeta col = meta.getColumns().get(i);
			sb.append("`").append(col.getName()).append("`");
			if (i < meta.getColumns().size() - 1) {
				sb.append(",");
			}
		}
		sb.append(" FROM `").append(tableName).append("`");

		if (option != null) {
			sb.append(buildWhereSQL(option, outCols, outValues));
		}
		return sb.toString();

	}

	protected String buildWhereSQL(Map<String, Object> options, List<ColumnMeta> outCols, List<Object> outValues) {
		// 使用索引
		int counter = 0;
		IndexMeta used = null;
		for (IndexMeta i : meta.getIndexes().values()) {
			int c = 0;
			for (String fieldName : options.keySet()) {
				ColumnMeta col = meta.getColumnMetaByFieldName(fieldName);
				if (col == null) {
					throw new RuntimeException(
							"filed[" + fieldName + "] not found in entity meta[" + meta.getName() + "]");
				}
				if (i.getColumns().contains(col.getName())) {
					c++;
				}
			}
			if (c > counter) {
				counter = c;
				used = i;
			}
		}

		// 没有索引或无法使用索引时，乱序生成where语句
		Iterator<String> it;
		if (used == null) {
			it = options.keySet().iterator();
			if (log.isWarnEnabled()) {
				StringBuilder sb = new StringBuilder();
				options.keySet().forEach(n -> sb.append(n).append(","));
				log.warn("no index found when query " + meta.getName() + " with options:" + sb.toString());
			}
		} else {
			List<String> list = new ArrayList<>();
			Set<String> clone = new HashSet<>(options.keySet());
			for (String colName : used.getColumns()) {
				if (options.containsKey(colName)) {
					list.add(colName);
					clone.remove(colName);
				}
			}
			list.addAll(clone);
			it = list.iterator();
		}
		StringBuilder sb = new StringBuilder();
		if (it.hasNext()) {
			sb.append(" WHERE ");
		}
		while (it.hasNext()) {
			String colName = it.next();
			Object value = options.get(colName);
			ColumnMeta col = meta.getColumnMetaByFieldName(colName);
			sb.append("`").append(col.getName()).append("` = ?");
			outCols.add(col);
			outValues.add(value);
			if (it.hasNext()) {
				sb.append(" AND ");
			}
		}
		return sb.toString();
	}

	/**
	 * 建表SQL
	 * 
	 * @param specialCluster
	 * @param tables
	 * @return
	 */
	protected List<String> buildCreateSQL(List<Integer> specialCluster, Set<String> tables) {
		List<String> resultList = new ArrayList<>();
		for (int i = 0; i < meta.getCluster(); i++) {// 已存在的则表示需要判断性的创建
			if (specialCluster.contains(i)) {
				continue;
			}
			tables.add(tabName(i));
			resultList.add(buildCreateSQL(tabName(i)));
		}
		return resultList;
	}

	/**
	 * 管理表建立
	 * 
	 * @return
	 */
	protected String buildCreateManagerSQL() {// 管理滚动表的管理表数据结构应直接包装好，保证结构体一致
		StringBuilder sb = new StringBuilder();
		sb.append("CREATE TABLE IF NOT EXISTS `").append(meta.getName() + ROLL_TYPE_SUFFIX).append("`(\n");
		// columns
		sb.append("`id`  int NOT NULL ,\\n");
		sb.append("`tableName`  varchar(64) NOT NULL ,\\n");
		sb.append("`createTime`  datetime NULL ,\\n");
		sb.append("`startSaveTime`  varchar(16) NOT NULL ,\\n");
		sb.append("`endSaveTime`  varchar(16) NOT NULL ,\\n");
		sb.append("PRIMARY KEY (`id`),\\n");
		sb.append("UNIQUE INDEX `uid_table_name` (`tableName`) \\n");
		sb.append(")");
		return sb.toString();
	}

	/**
	 * 管理表插入
	 * 
	 * @return
	 */
	protected String buildInsertManagerSQL() {// 插入语句定死在结构体
		StringBuilder sb = new StringBuilder();
		sb.append("INSERT INTO `").append(meta.getName() + ROLL_TYPE_SUFFIX).append("`(\n");
		sb.append("`tableName`,");
		sb.append("`createTime`,");
		sb.append("`startSaveTime`,");
		sb.append("`endSaveTime`");
		sb.append(") VALUE (").append("?,?,?,?").append(")");
		return sb.toString();
	}

	/**
	 * 管理表读取
	 * 
	 * @return
	 */
	protected String buildSelectManagerSQL() {// 查询所有表头信息，并缓存
		StringBuilder sb = new StringBuilder();
		sb.append("SELECT `tableName`,`createTime`,`startSaveTime`,`endSaveTime`");
		sb.append(" FROM (").append(meta.getName() + ROLL_TYPE_SUFFIX).append(")");
		return sb.toString();
	}

	// 如果管理表的数据和实际存在表不一致会出问题，所以还是要存在删除数据的SQL
	protected String buildDeleteManagerSQL(String tableName) {
		return "DELETE FROM `" + meta.getName() + ROLL_TYPE_SUFFIX + "` WHERE `tableName` =`" + tableName + "`";
	}

	private void prepareStatementManagerInsert(PreparedStatement ps, String tableName) throws Exception {
		ps.setString(1, tableName);
		ps.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
		ps.setString(3, tableName.split("_")[1]);
		ps.setString(4, getNextTimeString(tableName));
	}

	/**
	 * 检查表
	 * 
	 * @throws Exception
	 */
	protected void fixTable() throws Exception {
		Table annotation = getClassOfEntity().getAnnotation(Table.class);
		if (!annotation.autoCreate()) {
			return;
		}
		// 这里检查所有表名字，顺便把管理表一起检查了，管理表如果存在就直接更新一波数据，在每次插入管理表发生一次查询，保证管理表数据一致性
		List<Map<String, Object>> list = db.query("SHOW TABLES LIKE \"" + meta.getName() + "%\"");
		Set<String> tables = new HashSet<>();
		for (Map<String, Object> map : list) {
			for (Object v : map.values()) {
				tables.add(v.toString());
			}
		}
		if (!tables.contains(meta.getName() + ROLL_TYPE_SUFFIX)) {// 不存在管理表,就创建
			String buildManagerSql = buildCreateManagerSQL();
			db.update(buildManagerSql);
		} else {
			// 更新管理表数据
			rollTableMap = db.query(buildSelectManagerSQL());
		}

		List<Integer> specialClusters = new ArrayList<>();
		// 父类的检查无法避开分表策略，自行检查，也是分表策略的思路，可以将cluster字段复用为一个预建表的数量字段
		for (int i = 0; i < meta.getCluster(); i++) {
			if (tables.contains(tabName(i))) {
				TableMeta orig = TableMeta.getTableFromDB(tabName(i), db);
				diffEntityBetweenTable(meta, orig);
				specialClusters.add(i);
			}
		}

		if (specialClusters.size() < meta.getCluster()) {
			List<String> sql = buildCreateSQL(specialClusters, tables);
			for (String s : sql) {
				db.update(s);
			}
		}

		// 滚动表自检完，管理表检查数据是否匹配，是否需要插入新的表头数据
		checkManagerInfo(tables);
	}

	/**
	 * 比较表
	 * 
	 * @param meta
	 * @param orig
	 * @throws SQLException
	 */
	protected void diffEntityBetweenTable(TableMeta meta, TableMeta orig) throws SQLException {
		// 字段
		Set<String> mark = new HashSet<>();
		for (ColumnMeta columnMeta : meta.getColumns()) {
			mark.add(columnMeta.getName());
			ColumnMeta column = orig.getColumnMetaByColumnName(columnMeta.getName());
			// 新增
			if (column == null) {
				db.update("ALTER TABLE `" + orig.getName() + "` ADD COLUMN " + columnMeta.buildCreateSQL());
			} else {
				// 列依然存在，但有变动
				if (!column.isSame(columnMeta)) {
					// 列依然存在，但有变动
					db.update("ALTER TABLE `" + orig.getName() + "` MODIFY COLUMN " + columnMeta.buildCreateSQL());
				}
			}
		}

		// 索引
		mark.clear();
		for (IndexMeta indexMeta : meta.getIndexes().values()) {
			mark.add(indexMeta.getName());
			IndexMeta index = orig.getIndexes().get(indexMeta.getName());
			if (index == null) {
				db.update("ALTER TABLE `" + orig.getName() + "` ADD " + indexMeta.buildCreateSQL());
			} else {
				if (!index.isSame(indexMeta)) {
					db.update("DROP INDEX `" + indexMeta.getName() + "` on `" + meta.getName() + "`");
					db.update("ALTER TABLE `" + meta.getName() + "` ADD " + indexMeta.buildCreateSQL());
				}
			}
		}
		for (String indexName : orig.getIndexes().keySet()) {
			if (!mark.contains(indexName)) {
				db.update("DROP INDEX `" + indexName + "` on `" + orig.getName() + "`");
			}
		}
	}

	/**
	 * 检查对应的管理表数据
	 * 
	 * @param tables
	 * @throws Exception
	 */
	private void checkManagerInfo(Set<String> tables) throws Exception {
		for (Map<String, Object> manager : rollTableMap) {
			if (!tables.contains(manager.get("tableName").toString())) {
				// 当管理表里面存在的表头数据并没有实体表的时候，删除对应的表头信息
				db.update(buildDeleteManagerSQL(manager.get("tableName").toString()));
			}
		}
		for (Map<String, Object> manager : rollTableMap) {
			tables.remove(manager.get("tableName").toString());
		}
		tables.remove(meta.getName() + ROLL_TYPE_SUFFIX);
		if (!tables.isEmpty()) {
			String sql = buildInsertManagerSQL();
			if (log.isDebugEnabled()) {
				log.debug(sql);
			}
			Connection connection = null;
			PreparedStatement ps = null;
			try {
				connection = db.getConnection();
				connection.setAutoCommit(false);
				ps = connection.prepareStatement(sql);
				for (String tableName : tables) {
					prepareStatementManagerInsert(ps, tableName);
					ps.addBatch();
				}
				ps.executeBatch();
				connection.commit();
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			} finally {
				if (ps != null) {
					try {
						ps.close();
					} catch (SQLException e) {
						log.error("close statement failed", e);
					}
				}
				if (connection != null) {
					try {
						connection.setAutoCommit(true);
						connection.close();
					} catch (SQLException e) {
						log.error("close connection failed", e);
					}
				}
			}
		}

		rollTableMap = db.query(buildSelectManagerSQL());

	}

	/**
	 * 获取当前正在滚动的数据表名字
	 * 
	 * @return
	 */
	public String tabName() {
		if (currentTime <= System.currentTimeMillis() || tableName == null) {
			Calendar calendar = Calendar.getInstance();
			int year = calendar.get(Calendar.YEAR);
			int month = calendar.get(Calendar.MONTH) + 1;
			int day = calendar.get(Calendar.DAY_OF_MONTH);
			switch (rollingType) {
			case DAILY:
				tableName = meta.getName() + "_" + year + (month < 10 ? ("0" + month) : month)
						+ (day < 10 ? ("0" + day) : day);
				calendar.add(Calendar.DAY_OF_YEAR, 1);
				break;
			case WEEKLY:
				calendar.set(Calendar.DAY_OF_WEEK, 2);// 从星期一开始记
				year = calendar.get(Calendar.YEAR);
				month = calendar.get(Calendar.MONTH) + 1;
				day = calendar.get(Calendar.DAY_OF_MONTH);
				tableName = meta.getName() + "_" + year + (month < 10 ? ("0" + month) : month)
						+ (day < 10 ? ("0" + day) : day);
				calendar.add(Calendar.WEEK_OF_YEAR, 1);
				break;
			case MONTHLY:
				tableName = meta.getName() + "_" + year + (month < 10 ? ("0" + month) : month) + "01";
				calendar.add(Calendar.MONTH, 1);
				break;
			default:
				tableName = meta.getName();
			}
			currentTime = calendar.getTimeInMillis();
		}
		return tableName;

	}

	/**
	 * 获取间隔时间后的数据表名字
	 * 
	 * @return
	 */
	public String tabName(int interval) {
		Calendar calendar = Calendar.getInstance();
		switch (rollingType) {
		case DAILY:
			calendar.add(Calendar.DAY_OF_YEAR, interval);
			break;
		case WEEKLY:
			calendar.add(Calendar.WEEK_OF_YEAR, interval);
			break;
		case MONTHLY:
			calendar.add(Calendar.MONTH, interval);
			break;
		default:
			break;
		}
		int year = calendar.get(Calendar.YEAR);
		int month = calendar.get(Calendar.MONTH) + 1;
		int day = calendar.get(Calendar.DAY_OF_MONTH);
		switch (rollingType) {
		case DAILY:
			return meta.getName() + "_" + year + (month < 10 ? ("0" + month) : month) + (day < 10 ? ("0" + day) : day);
		case WEEKLY:
			calendar.set(Calendar.DAY_OF_WEEK, 2);// 从星期一开始记
			year = calendar.get(Calendar.YEAR);
			month = calendar.get(Calendar.MONTH) + 1;
			day = calendar.get(Calendar.DAY_OF_MONTH);
			return meta.getName() + "_" + year + (month < 10 ? ("0" + month) : month) + (day < 10 ? ("0" + day) : day);
		case MONTHLY:
			return meta.getName() + "_" + year + (month < 10 ? ("0" + month) : month) + "01";
		default:
			return meta.getName();
		}
	}

	public String getNextTimeString(String tabName) {
		String time = tabName.split("_")[1];
		Calendar calendar = Calendar.getInstance();
		calendar.set(Calendar.YEAR, Integer.valueOf(time.substring(0, 4)));
		calendar.set(Calendar.MONTH, Integer.valueOf(time.substring(4, 6)) - 1);
		calendar.set(Calendar.DAY_OF_MONTH, Integer.valueOf(time.substring(6)));

		switch (rollingType) {
		case DAILY:
			calendar.add(Calendar.DAY_OF_YEAR, 1);
			break;
		case WEEKLY:
			calendar.add(Calendar.WEEK_OF_YEAR, 1);
			break;
		case MONTHLY:
			calendar.add(Calendar.MONTH, 1);
			break;
		default:
			break;
		}

		int year = calendar.get(Calendar.YEAR);
		int month = calendar.get(Calendar.MONTH) + 1;
		int day = calendar.get(Calendar.DAY_OF_MONTH);
		switch (rollingType) {
		case DAILY:
			return year + "" + (month < 10 ? ("0" + month) : month) + (day < 10 ? ("0" + day) : day);
		case WEEKLY:
			calendar.set(Calendar.DAY_OF_WEEK, 2);// 从星期一开始记
			year = calendar.get(Calendar.YEAR);
			month = calendar.get(Calendar.MONTH) + 1;
			day = calendar.get(Calendar.DAY_OF_MONTH);
			return year + "" + (month < 10 ? ("0" + month) : month) + (day < 10 ? ("0" + day) : day);
		case MONTHLY:
			return year + "" + (month < 10 ? ("0" + month) : month) + "01";
		default:
			return "";
		}

	}

	@SuppressWarnings("unchecked")
	protected List<T> parse(TableMeta meta, List<Map<String, Object>> rs) throws SQLException {
		List<T> list = new ArrayList<>();
		try {
			for (Map<String, Object> map : rs) {
				T t = (T) meta.getClazz().newInstance();
				for (ColumnMeta col : meta.getColumns()) {
					if (map.containsKey(col.getName())) {
						col.cast(t, map.get(col.getName()));
					}
				}
				list.add(t);
			}
		} catch (Exception e) {
			throw new SQLException("build entity from result set failed", e);
		}
		return list;
	}
}