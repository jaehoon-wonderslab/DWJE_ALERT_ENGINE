package com.dwje.alert.config

import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * 대상 PostgreSQL 한 곳만 쓴다.
 *
 * 이관 엔진과 달리 원천이 따로 없다 — 알림이 보는 값은 이미 PostgreSQL 에 들어와 있다.
 * (mes 스키마는 이관 엔진이, ax 스키마는 API 와 이 엔진이 채운다)
 *
 * 질의는 전부 `NamedParameterJdbcTemplate` 으로 나간다. 사내 규칙의
 * Native SQL Direct Binding(iBatis/MyBatis 미사용)을 API 와 맞춘 것이다.
 */
@Configuration
class DataSourceConfig(private val props: AlertProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun dataSource(): DataSource {
        log.info("DB 접속 구성 — {}", maskUrl(props.db.url))
        return DataSourceBuilder.create()
            .driverClassName("org.postgresql.Driver")
            .url(props.db.url)
            .username(props.db.username)
            .password(props.db.password)
            .build()
            .also { ds ->
                (ds as? HikariDataSource)?.apply {
                    maximumPoolSize = props.db.maxPoolSize
                    poolName = "alert-engine"
                    connectionTimeout = 30_000
                }
            }
    }

    @Bean
    fun jdbcTemplate(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource)

    @Bean
    fun namedJdbc(dataSource: DataSource): NamedParameterJdbcTemplate =
        NamedParameterJdbcTemplate(dataSource)

    @Bean
    fun transactionManager(dataSource: DataSource): PlatformTransactionManager =
        DataSourceTransactionManager(dataSource)

    @Bean
    fun transactionTemplate(tm: PlatformTransactionManager): TransactionTemplate =
        TransactionTemplate(tm)

    /** 로그에 접속 문자열을 남길 때 비밀번호 파라미터를 가린다 */
    private fun maskUrl(url: String): String =
        url.replace(Regex("(?i)(password=)[^;&]*"), "$1****")
}
