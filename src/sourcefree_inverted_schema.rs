#[cfg(test)]
mod tests {
    #[derive(Debug, PartialEq, Eq)]
    struct Schema<'a> {
        name: &'a str,
        fields: &'a [(&'a str, &'a str)],
    }

    fn compatible(left: &Schema<'_>, right: &Schema<'_>) -> bool {
        left.name == right.name && left.fields == right.fields
    }

    #[test]
    fn same_name_physical_schema_substitution_is_rejected() {
        let expected_fields = [("x", "f64"), ("y", "f64")];
        let expected = Schema {
            name: "Particle",
            fields: &expected_fields,
        };
        assert!(compatible(&expected, &expected));

        let wrong_kind_fields = [("x", "i64"), ("y", "f64")];
        let wrong_kind = Schema {
            name: "Particle",
            fields: &wrong_kind_fields,
        };
        assert!(!compatible(&wrong_kind, &expected));

        let wrong_order_fields = [("y", "f64"), ("x", "f64")];
        let wrong_order = Schema {
            name: "Particle",
            fields: &wrong_order_fields,
        };
        assert!(!compatible(&wrong_order, &expected));

        let record_expected_fields = [("sample", "record(ok:bool@0,x:f64@1)/9")];
        let record_wrong_fields = [("sample", "record(ok:bool@0,x:i64@1)/9")];
        let record_expected = Schema {
            name: "Particle",
            fields: &record_expected_fields,
        };
        let record_wrong = Schema {
            name: "Particle",
            fields: &record_wrong_fields,
        };
        assert!(!compatible(&record_wrong, &record_expected));
    }
}
