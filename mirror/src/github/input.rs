use crate::error::RuntimeError;
use crate::org::validate_org_login;

pub(super) fn owner(value: &str) -> Result<String, RuntimeError> {
    let value = validate_org_login(value)?;
    if value.starts_with('-') {
        return Err(RuntimeError::Usage(
            "GitHub owner must not begin with an option prefix".to_owned(),
        ));
    }
    Ok(value)
}

pub(super) fn repository_name(value: &str) -> Result<(), RuntimeError> {
    if value.is_empty()
        || value.len() > 100
        || matches!(value, "." | "..")
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"._-".contains(&byte))
    {
        return Err(RuntimeError::Usage(
            "invalid GitHub repository name".to_owned(),
        ));
    }
    Ok(())
}

pub(super) fn repository(value: &str) -> Result<String, RuntimeError> {
    let (login, name) = value.split_once('/').ok_or_else(|| {
        RuntimeError::Usage("repository must have the form owner/name".to_owned())
    })?;
    let login = owner(login)?;
    repository_name(name)?;
    Ok(format!("{login}/{name}"))
}

/// Encode a literal repository path, never query parameters or a traversal.
/// Percent signs in filenames are encoded too; no caller can smuggle an encoded
/// slash or `?ref=` into the GitHub endpoint assembled by the SDK.
pub(super) fn contents_path(value: &str) -> Result<String, RuntimeError> {
    if value.is_empty()
        || value.len() > 4096
        || value
            .chars()
            .any(|character| character.is_control() || character == '\\')
        || value
            .split('/')
            .any(|segment| matches!(segment, "" | "." | ".."))
    {
        return Err(RuntimeError::Usage(
            "repository path must be a nonempty relative path without traversal".to_owned(),
        ));
    }
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut encoded = String::with_capacity(value.len());
    for byte in value.bytes() {
        if byte.is_ascii_alphanumeric() || b"-._~/".contains(&byte) {
            encoded.push(char::from(byte));
        } else {
            encoded.push('%');
            encoded.push(char::from(HEX[usize::from(byte >> 4)]));
            encoded.push(char::from(HEX[usize::from(byte & 15)]));
        }
    }
    Ok(encoded)
}

pub(super) fn visibility(value: &str) -> Result<(), RuntimeError> {
    if matches!(value, "private" | "internal" | "public") {
        Ok(())
    } else {
        Err(RuntimeError::Usage(
            "repository visibility must be private, internal, or public".to_owned(),
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn owner_cannot_be_a_gh_option_or_endpoint() {
        for value in ["--help", "-R", "", "a/b", "a?b", "a#b", "a%2fb", "a\nb"] {
            assert!(owner(value).is_err(), "accepted {value:?}");
        }
        assert_eq!(owner(" ORESoftware ").expect("safe owner"), "ORESoftware");
        assert_eq!(owner("flags-2-env").expect("safe owner"), "flags-2-env");
    }

    #[test]
    fn full_repository_has_exactly_two_safe_segments() {
        for value in [
            "org",
            "org/",
            "/repo",
            "org/a/b",
            "org/..",
            "org/.",
            "org/a?ref=x",
            "org/a#x",
            "org/%2e",
            "--help/repo",
        ] {
            assert!(repository(value).is_err(), "accepted {value:?}");
        }
        assert_eq!(
            repository("org/.github").expect("safe repository"),
            "org/.github"
        );
        assert_eq!(
            repository("org/app-server.rs").expect("safe repository"),
            "org/app-server.rs"
        );
    }

    #[test]
    fn repository_names_have_bounded_ascii_syntax() {
        for value in ["", ".", "..", "a/b", "a b", "a\0b", "é", "a?b"] {
            assert!(repository_name(value).is_err(), "accepted {value:?}");
        }
        assert!(repository_name(&"a".repeat(100)).is_ok());
        assert!(repository_name(&"a".repeat(101)).is_err());
    }

    #[test]
    fn contents_path_preserves_literal_filename_semantics() {
        assert_eq!(
            contents_path("src/lib.rs").expect("safe path"),
            "src/lib.rs"
        );
        assert_eq!(
            contents_path("docs/a b.md").expect("safe path"),
            "docs/a%20b.md"
        );
        assert_eq!(
            contents_path("README.md?ref=other").expect("safe path"),
            "README.md%3Fref%3Dother"
        );
        assert_eq!(contents_path("a#b").expect("safe path"), "a%23b");
        assert_eq!(contents_path("a%2fb").expect("safe path"), "a%252fb");
        assert_eq!(
            contents_path("docs/é.md").expect("safe path"),
            "docs/%C3%A9.md"
        );
    }

    #[test]
    fn contents_path_rejects_traversal_and_controls() {
        for value in [
            "", "/a", "a/", "a//b", ".", "..", "a/../b", "a/./b", "a\\b", "a\nb", "a\0b",
        ] {
            assert!(contents_path(value).is_err(), "accepted {value:?}");
        }
        assert!(contents_path(&"a".repeat(4097)).is_err());
    }

    #[test]
    fn mutation_visibility_is_an_explicit_enum() {
        for value in ["private", "internal", "public"] {
            assert!(visibility(value).is_ok());
        }
        for value in ["", "PUBLIC", "public&auto_init=false", "private\n"] {
            assert!(visibility(value).is_err());
        }
    }
}
