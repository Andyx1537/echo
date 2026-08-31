package com.aengine.template;

import java.io.*;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 压缩文件
 * 
 */
public class ZipFileUtil {
	/**
	 * 将存放在sourceFilePath目录下的源文件，打包成fileName名称的zip文件，并存放到zipFilePath路径下
	 * 
	 * @param sourceFilePath
	 *            :待压缩的文件路径
	 * @param zipFilePath
	 *            :压缩后存放路径
	 * @param fileName
	 *            :压缩后文件的名称
	 * @param filter
	 *            :过滤文件集合
	 */
	public static void filePathToZip(String sourceFilePath, String zipFilePath, String fileName, Set<String> filter) {
		File sourceFile = new File(sourceFilePath);
		if (sourceFile.exists() == false) {
			System.out.println("待压缩的文件目录：" + sourceFilePath + "不存在.");
			return;
		}

		ZipOutputStream zos = null;
		try {
			File zipFile = new File(zipFilePath + "/" + fileName + ".zip");
			File[] sourceFiles = sourceFile.listFiles();
			if (null == sourceFiles || sourceFiles.length < 1) {
				System.out.println("待压缩的文件目录：" + sourceFilePath + "里面不存在文件，无需压缩.");
				return;
			}
			zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)));
			for (int i = 0; i < sourceFiles.length; i++) {
				if (!sourceFiles[i].getName().endsWith(".txt"))
					continue;
				if (filter.contains(sourceFiles[i].getName()))
					continue;
				// 并添加进压缩包
				addfileToZip(zos, sourceFiles[i]);
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		} finally {
			// 关闭流
			try {
				if (null != zos)
					zos.close();
			} catch (IOException e) {
				e.printStackTrace();
				throw new RuntimeException(e);
			}
		}
	}

	/**
	 * 压缩文件
	 * 
	 * @param sourceFilePath 待压缩的文件路径
	 * @param zipFilePath 压缩后文件的名称
	 * @param zipFileName 待压缩的文件路径
	 * @param fileNames 待压缩的文件集合
	 */
	public static void filesToZip(String sourceFilePath, String zipFilePath, String zipFileName,
			Set<String> fileNames) {
		File sourceFile = new File(sourceFilePath);
		if (sourceFile.exists() == false) {
			System.out.println("待压缩的文件目录：" + sourceFilePath + "不存在.");
			return;
		}

		ZipOutputStream zos = null;
		try {
			File zipFile = new File(zipFilePath + "/" + zipFileName + ".zip");
			zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)));
			for (String fileName : fileNames) {
				File inFile = new File(sourceFilePath, fileName);
				if (!inFile.exists()) {
					System.out.println("待压缩的文件：" + inFile + "不存在");
					continue;
				}
				// 并添加进压缩包
				addfileToZip(zos, inFile);
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		} finally {
			// 关闭流
			try {
				if (null != zos)
					zos.close();
			} catch (IOException e) {
				e.printStackTrace();
				throw new RuntimeException(e);
			}
		}
	}

	/**
	 * 添加文件压缩
	 * 
	 * @param zos 压缩输出流
	 * @param inFile 被压缩文件
	 */
	private static void addfileToZip(ZipOutputStream zos, File inFile) {
		byte[] bufs = new byte[1024 * 10];
		// 创建ZIP实体，并添加进压缩包
		ZipEntry zipEntry = new ZipEntry(inFile.getName());
		BufferedInputStream bis = null;
		try {
			zos.putNextEntry(zipEntry);
			// 读取待压缩的文件并写进压缩包里
			bis = new BufferedInputStream(new FileInputStream(inFile), 1024 * 10);
			int read = 0;
			while ((read = bis.read(bufs, 0, 1024 * 10)) != -1) {
				zos.write(bufs, 0, read);
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			// 关闭流
			try {
				if (bis != null)
					bis.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
