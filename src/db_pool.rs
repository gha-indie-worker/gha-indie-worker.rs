//! Production SeaORM/SQLx PostgreSQL connection with a capability gate.
//! This module is also compiled unmodified by the isolated live-database CI
//! harness. No schema migration is performed by connect().
use crate::db_capabilities::{DatabaseCapabilityProfile, DatabaseProfileError};
use sea_orm::sqlx::postgres::{PgConnectOptions, PgPoolOptions};
use sea_orm::{DatabaseConnection, SqlxPostgresConnector};
use std::time::Duration;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum DatabaseConnectError {
    Profile(DatabaseProfileError),
    InvalidDatabaseUrl,
    ConnectFailed,
}

pub(crate) async fn connect(
    database_url: &str,
    profile: DatabaseCapabilityProfile,
) -> Result<DatabaseConnection, DatabaseConnectError> {
    if !profile.permits_write_pool() {
        return Err(DatabaseConnectError::Profile(
            DatabaseProfileError::ReadOnlyOrMigrator,
        ));
    }

    let application_name = format!("dd-build-server-{}", profile.as_str());
    let options = database_url
        .parse::<PgConnectOptions>()
        .map_err(|_| DatabaseConnectError::InvalidDatabaseUrl)?
        .application_name(&application_name);
    let pool = PgPoolOptions::new()
        .max_connections(8)
        .min_connections(1)
        .acquire_timeout(Duration::from_secs(10))
        .idle_timeout(Some(Duration::from_secs(300)))
        .connect_with(options)
        .await
        .map_err(|_| DatabaseConnectError::ConnectFailed)?;
    let db = SqlxPostgresConnector::from_sqlx_postgres_pool(pool);
    tracing::info!(
        database_profile = profile.as_str(),
        "build-server database connected; migrations are not run at boot — converge the schema \
         with scripts/dpm.sh (contract: remote/libs/pg-defs/schema/databases/dd_build_server)"
    );
    Ok(db)
}
