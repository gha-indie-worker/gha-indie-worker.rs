//! Compile the real production database connection path in isolation.
//! Unlike source-grep assertions, this exercises SeaORM/SQLx types and queries.
//! These tests still do not compile the full server or its sibling dependencies.
#[path = "../../../src/db_capabilities.rs"]
mod db_capabilities;
#[path = "../../../src/db_pool.rs"]
mod db_pool;

#[cfg(test)]
mod integration_tests {
    use super::db_capabilities::{DatabaseCapabilityProfile as Profile, DatabaseProfileError};
    use super::db_pool::{connect, DatabaseConnectError};
    use sea_orm::{ConnectionTrait, DbBackend, Statement};

    #[tokio::test]
    async fn forbidden_profiles_reject_even_malformed_urls_before_network_io() {
        for profile in [Profile::WebReadOnly, Profile::WorkerReadOnly, Profile::Migrator] {
            let result = connect("invalid:///not-a-database", profile).await;
            assert!(matches!(
                result,
                Err(DatabaseConnectError::Profile(
                    DatabaseProfileError::ReadOnlyOrMigrator
                ))
            ));
        }
    }

    #[tokio::test]
    async fn permitted_profiles_still_reject_invalid_connection_urls() {
        for profile in [Profile::ApiReadWrite, Profile::WorkerReadWrite] {
            let result = connect("not-a-postgres-connection-url", profile).await;
            assert!(matches!(result, Err(DatabaseConnectError::InvalidDatabaseUrl)));
        }
    }

    #[tokio::test]
    async fn writable_profiles_open_real_postgres_and_tag_the_session() {
        let url = std::env::var("TEST_DATABASE_URL")
            .expect("TEST_DATABASE_URL must point to an isolated CI PostgreSQL instance");
        for profile in [Profile::ApiReadWrite, Profile::WorkerReadWrite] {
            let db = connect(&url, profile)
                .await
                .expect("approved profile must connect to the CI PostgreSQL service");
            assert_eq!(db.get_database_backend(), DbBackend::Postgres);
            let row = db
                .query_one(Statement::from_string(
                    DbBackend::Postgres,
                    "SELECT current_setting('application_name') AS app_name".to_string(),
                ))
                .await
                .expect("real SQLx query")
                .expect("application name row");
            let app_name: String = row.try_get("", "app_name").expect("application name column");
            assert_eq!(app_name, format!("dd-build-server-{}", profile.as_str()));
        }
    }

    #[tokio::test]
    async fn connection_does_not_run_migrations_on_empty_ci_database() {
        let url = std::env::var("TEST_DATABASE_URL")
            .expect("TEST_DATABASE_URL must point to an isolated CI PostgreSQL instance");
        let db = connect(&url, Profile::ApiReadWrite)
            .await
            .expect("approved profile must connect");
        let row = db
            .query_one(Statement::from_string(
                DbBackend::Postgres,
                "SELECT COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema = 'public'".to_string(),
            ))
            .await
            .expect("real PostgreSQL query")
            .expect("table count row");
        let table_count: i64 = row.try_get("", "table_count").expect("table count column");
        assert_eq!(table_count, 0, "database connect must not create schema");
    }
}
