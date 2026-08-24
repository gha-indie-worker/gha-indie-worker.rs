extern crate self as dd_nats_subject_defs;

pub const FABRICATION_REQUESTS_SUBJECT: &str = "dd.remote.fabrication.requests";
pub const FABRICATION_RESULTS_SUBJECT: &str = "dd.remote.fabrication.results";

mod included_job_control_shape {
    pub fn subjects() -> (&'static str, &'static str) {
        (
            dd_nats_subject_defs::FABRICATION_REQUESTS_SUBJECT,
            dd_nats_subject_defs::FABRICATION_RESULTS_SUBJECT,
        )
    }
}

fn main() {
    let _ = included_job_control_shape::subjects();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crate_alias_exposes_canonical_subject_constants_to_included_module() {
        assert_eq!(
            included_job_control_shape::subjects(),
            (
                "dd.remote.fabrication.requests",
                "dd.remote.fabrication.results"
            )
        );
    }
}
