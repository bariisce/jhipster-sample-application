package com.myapp.csm.repository;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.BatchStatementBuilder;
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder;
import com.datastax.oss.driver.api.core.cql.DefaultBatchType;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.mapper.annotations.Dao;
import com.datastax.oss.driver.api.mapper.annotations.DaoFactory;
import com.datastax.oss.driver.api.mapper.annotations.DaoKeyspace;
import com.datastax.oss.driver.api.mapper.annotations.Delete;
import com.datastax.oss.driver.api.mapper.annotations.Mapper;
import com.datastax.oss.driver.api.mapper.annotations.Select;
import com.myapp.csm.domain.PersistentToken;
import com.myapp.csm.domain.User;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.cassandra.CassandraProperties;
import org.springframework.stereotype.Repository;

/**
 * Cassandra repository for the {@link PersistentToken} entity.
 */
@Repository
public class PersistentTokenRepository {

    private final CqlSession session;

    private final Validator validator;

    PersistentTokenDao persistentTokenDao;

    private PreparedStatement findPersistentTokenSeriesByUserIdStmt;

    private PreparedStatement insertPersistentTokenSeriesByUserIdStmt;

    private PreparedStatement insertPersistentTokenStmt;

    private PreparedStatement deletePersistentTokenSeriesByUserIdStmt;

    public PersistentTokenRepository(CqlSession session, Validator validator, CassandraProperties cassandraProperties) {
        this.session = session;
        this.validator = validator;
        PersistentTokenMapper persistentTokenMapper = new PersistentTokenMapperBuilder(session).build();
        persistentTokenDao = persistentTokenMapper.persistentTokenDao(CqlIdentifier.fromCql(cassandraProperties.getKeyspaceName()));

        findPersistentTokenSeriesByUserIdStmt =
            session.prepare("SELECT persistent_token_series " + "FROM persistent_token_by_user " + "WHERE user_id = :user_id");

        insertPersistentTokenSeriesByUserIdStmt =
            session.prepare(
                "INSERT INTO persistent_token_by_user (user_id, persistent_token_series) " +
                "VALUES (:user_id, :persistent_token_series) " +
                "USING TTL 2592000"
            ); // 30 days

        insertPersistentTokenStmt =
            session.prepare(
                "INSERT INTO persistent_token (series, token_date, user_agent, token_value, login, user_id, ip_address) " +
                "VALUES (:series, :token_date, :user_agent, :token_value, :login, :user_id, :ip_address) " +
                "USING TTL 2592000"
            ); // 30 days

        deletePersistentTokenSeriesByUserIdStmt =
            session.prepare(
                "DELETE FROM persistent_token_by_user WHERE user_id = :user_id AND persistent_token_series = :persistent_token_series"
            );
    }

    public Optional<PersistentToken> findById(String presentedSeries) {
        return Optional.ofNullable(persistentTokenDao.get(presentedSeries));
    }

    public List<PersistentToken> findByUser(User user) {
        BoundStatementBuilder statementBuilder = findPersistentTokenSeriesByUserIdStmt.boundStatementBuilder();
        statementBuilder.setString("user_id", user.getId());
        ResultSet rs = session.execute(statementBuilder.build());
        List<PersistentToken> persistentTokens = new ArrayList<>();
        rs
            .all()
            .stream()
            .map(row -> row.getString("persistent_token_series"))
            .map(token -> persistentTokenDao.get(token))
            .forEach(persistentTokens::add);

        return persistentTokens;
    }

    public void save(PersistentToken token) {
        Set<ConstraintViolation<PersistentToken>> violations = validator.validate(token);
        if (violations != null && !violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        BatchStatementBuilder batch = BatchStatement.builder(DefaultBatchType.LOGGED);
        batch.addStatement(
            insertPersistentTokenStmt
                .bind()
                .setString("series", token.getSeries())
                .setInstant("token_date", token.getTokenDate())
                .setString("user_agent", token.getUserAgent())
                .setString("token_value", token.getTokenValue())
                .setString("login", token.getLogin())
                .setString("user_id", token.getUserId())
                .setString("ip_address", token.getIpAddress())
        );
        batch.addStatement(
            insertPersistentTokenSeriesByUserIdStmt
                .bind()
                .setString("user_id", token.getUserId())
                .setString("persistent_token_series", token.getSeries())
        );
        session.execute(batch.build());
    }

    public void delete(PersistentToken token) {
        persistentTokenDao.delete(token);
        session.execute(
            deletePersistentTokenSeriesByUserIdStmt
                .bind()
                .setString("user_id", token.getUserId())
                .setString("persistent_token_series", token.getSeries())
        );
    }
}

@Mapper
interface PersistentTokenMapper {
    @DaoFactory
    PersistentTokenDao persistentTokenDao(@DaoKeyspace CqlIdentifier keyspace);
}

@Dao
interface PersistentTokenDao {
    @Select
    PersistentToken get(String presentedSeries);

    @Delete
    void delete(PersistentToken token);
}
