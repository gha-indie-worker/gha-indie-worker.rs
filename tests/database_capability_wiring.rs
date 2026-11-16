//! Source-wiring assertions are intentionally *not* full database integration tests.
//! They fail closed if the guarded pool construction or startup profile check
//! is removed or reordered. Run as standalone Rust tests in hosted CI.
use std::fs;

fn read(path: &str) -> String {
    fs::read_to_string(path).unwrap_or_else(|error| panic!("{path}: {error}"))
}

#[test]
fn pool_authority_gate_precedes_url_parsing_and_network_io() {
    let db = read("src/db.rs");
    let guard = db
        .find("if !profile.permits_write_pool()")
        .expect("write authority guard");
    let parse = db
        .find(".parse::<PgConnectOptions>()")
        .expect("SQLx URL parse");
    let connection = db
        .find(".connect_with(options)")
        .expect("network connection");
    assert!(
        guard < parse && parse < connection,
        "profile must be checked first"
    );
    assert!(db.contains("DatabaseProfileError::ReadOnlyOrMigrator"));
}

#[test]
fn server_requires_explicit_profile_for_configured_database() {
    let main = read("src/main.rs");
    assert!(main.contains("mod db_capabilities;"));
    let configured = main
        .find("Some(url) => {")
        .expect("configured database path");
    let env = main
        .find("first_env(&[\"BUILD_SERVER_DATABASE_PROFILE\"])")
        .expect("profile lookup");
    let parse = main
        .find("DatabaseCapabilityProfile::parse(&profile_name)")
        .expect("profile parse");
    let connect = main
        .find("db::connect(url, profile)")
        .expect("explicit profile to connect");
    assert!(configured < env && env < parse && parse < connect);
    assert!(main.contains("BUILD_SERVER_DATABASE_PROFILE is missing"));
    assert!(main.contains("BUILD_SERVER_DATABASE_PROFILE does not permit"));
}

#[test]
fn connection_failure_does_not_log_password_or_run_migrations() {
    let main = read("src/main.rs");
    assert!(main.contains("let _ = error;"));
    assert!(main.contains("connect failed (message suppressed to avoid leaking the DSN)"));
    assert!(!main.contains("Database::migrate"));
    let db = read("src/db.rs");
    assert!(!db.contains("Database::migrate"));
}

// Compile the production capability implementation as a sibling module, as
// src/db.rs imports it. A test compiled inside db_capabilities.rs alone cannot
// detect accidental private visibility on permits_write_pool().
#[path = "../src/db_capabilities.rs"]
mod db_capabilities;

mod sibling_database_caller {
    use super::db_capabilities::DatabaseCapabilityProfile;

    pub(super) fn permits_write_pool(profile: DatabaseCapabilityProfile) -> bool {
        profile.permits_write_pool()
    }
}

#[test]
fn database_sibling_module_can_call_capability_guard() {
    use db_capabilities::DatabaseCapabilityProfile as Profile;

    assert!(sibling_database_caller::permits_write_pool(
        Profile::ApiReadWrite
    ));
    assert!(sibling_database_caller::permits_write_pool(
        Profile::WorkerReadWrite
    ));
    assert!(!sibling_database_caller::permits_write_pool(
        Profile::WebReadOnly
    ));
    assert!(!sibling_database_caller::permits_write_pool(
        Profile::WorkerReadOnly
    ));
    assert!(!sibling_database_caller::permits_write_pool(
        Profile::Migrator
    ));
}
