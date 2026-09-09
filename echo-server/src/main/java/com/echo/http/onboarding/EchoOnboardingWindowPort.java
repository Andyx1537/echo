package com.echo.http.onboarding;

import com.aengine.util.id.IDGenerator;
import com.echo.http.Temperature;
import com.echo.http.model.Models.AccountProfile;
import com.echo.http.model.Models.PetProfile;
import com.echo.http.store.EchoStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Adapter to the existing private-window persistence boundary. */
public final class EchoOnboardingWindowPort implements OnboardingWindowPort {
    private final EchoStore store;
    private final IDGenerator ids;

    public EchoOnboardingWindowPort(EchoStore store, IDGenerator ids) {
        this.store = store;
        this.ids = ids;
    }

    @Override
    public synchronized Result confirm(OnboardingAggregate s, OnboardingAggregate.Candidate candidate,
                                       Connection transaction) throws SQLException {
        if (s.confirmedPetId != null) return new Result(s.confirmedPetId, s.confirmedWindowId);
        if (transaction != null) return confirmPersistent(transaction, s, candidate);
        PetProfile existing = store.petOfOwner(s.accountId);
        if (existing != null) return new Result(existing.petId, existing.petId);
        PetProfile pet = new PetProfile();
        pet.petId = String.valueOf(ids.nextId());
        pet.ownerAccountId = s.accountId;
        pet.name = normalizeName(s.petName);
        OnboardingAggregate.Subject subject = s.subjects.stream()
                .filter(value -> value.subjectId.equals(s.selectedSubjectId)).findFirst().orElse(null);
        pet.species = subject == null || subject.species == null ? "毛孩子" : subject.species;
        pet.signature = candidate.signature;
        pet.temperature = Temperature.normalize(72.0);
        pet.visibility = "private";
        pet.coverGradient = candidate.gradient;
        pet.coverEmoji = candidate.emoji;
        pet.createTime = System.currentTimeMillis();
        store.putPet(pet);
        AccountProfile profile = store.profile(s.accountId);
        if (profile != null) {
            profile.hasPet = true;
            store.putProfile(profile);
        }
        return new Result(pet.petId, pet.petId);
    }

    private Result confirmPersistent(Connection c, OnboardingAggregate s,
                                     OnboardingAggregate.Candidate candidate) throws SQLException {
        try (PreparedStatement lock = c.prepareStatement(
                "SELECT \"accountId\" FROM \"t_account_profile\" WHERE \"accountId\"=? FOR UPDATE")) {
            lock.setLong(1, s.accountId);
            try (ResultSet rows = lock.executeQuery()) {
                if (!rows.next()) throw new SQLException("onboarding account is missing");
            }
        }
        try (PreparedStatement find = c.prepareStatement(
                "SELECT \"petId\" FROM \"t_pet\" WHERE \"ownerAccountId\"=? "
                        + "ORDER BY \"createTime\" ASC LIMIT 1")) {
            find.setLong(1, s.accountId);
            try (ResultSet rows = find.executeQuery()) {
                if (rows.next()) {
                    String petId = String.valueOf(rows.getLong(1));
                    return new Result(petId, petId);
                }
            }
        }
        String petId = String.valueOf(ids.nextId());
        OnboardingAggregate.Subject subject = s.subjects.stream()
                .filter(value -> value.subjectId.equals(s.selectedSubjectId)).findFirst().orElse(null);
        String species = subject == null || subject.species == null ? "毛孩子" : subject.species;
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO \"t_pet\"(\"petId\",\"ownerAccountId\",\"name\",\"species\","
                        + "\"signature\",\"temperature\",\"visibility\",\"coverGradient\","
                        + "\"coverEmoji\",\"createTime\") VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            insert.setLong(1, Long.parseLong(petId));
            insert.setLong(2, s.accountId);
            insert.setString(3, normalizeName(s.petName));
            insert.setString(4, species);
            insert.setString(5, limit(candidate.signature, 256));
            insert.setDouble(6, Temperature.normalize(72.0));
            insert.setString(7, "private");
            insert.setString(8, candidate.gradient == null ? "" : candidate.gradient);
            insert.setString(9, candidate.emoji == null ? "" : candidate.emoji);
            insert.setLong(10, System.currentTimeMillis());
            insert.executeUpdate();
        }
        try (PreparedStatement profile = c.prepareStatement(
                "UPDATE \"t_account_profile\" SET \"hasPet\"=1 WHERE \"accountId\"=?")) {
            profile.setLong(1, s.accountId);
            if (profile.executeUpdate() != 1) throw new SQLException("onboarding account update failed");
        }
        return new Result(petId, petId);
    }

    private static String limit(String value, int maxCodePoints) {
        if (value == null) return "";
        if (value.codePointCount(0, value.length()) <= maxCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private static String normalizeName(String value) {
        return value == null || value.isBlank() ? "它" : value.trim();
    }
}
