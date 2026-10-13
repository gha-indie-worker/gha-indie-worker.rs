//! A conservative source-level gate for packaged native Oreslang stdlib modules.
//! This does NOT replace compiler admission, sandboxing, or runtime capability checks.
use std::{env, fs, path::Path, process};

const MAX_FILE: u64 = 2 * 1024 * 1024;
const MAX_FILES: usize = 2048;

#[derive(Clone, Debug, PartialEq)]
enum Kind {
    Name,
    Literal,
    Symbol(char),
}
#[derive(Clone, Debug)]
struct Token {
    kind: Kind,
    text: String,
    line: usize,
}
impl Token {
    fn name(&self, name: &str) -> bool {
        self.kind == Kind::Name && self.text == name
    }
    fn symbol(&self, c: char) -> bool {
        self.kind == Kind::Symbol(c)
    }
}

fn lex(s: &str) -> Result<Vec<Token>, String> {
    let b = s.as_bytes();
    let (mut p, mut line, mut out) = (0, 1, Vec::new());
    while p < b.len() {
        let start = p;
        match b[p] {
            b'\n' => {
                line += 1;
                p += 1;
            }
            b' ' | b'\r' | b'\t' => p += 1,
            b'/' if b.get(p + 1) == Some(&b'/') => {
                p += 2;
                while p < b.len() && b[p] != b'\n' {
                    p += 1;
                }
            }
            b'/' if b.get(p + 1) == Some(&b'*') => {
                p += 2;
                let mut depth = 1;
                while p < b.len() && depth > 0 {
                    if b[p..].starts_with(b"/*") {
                        depth += 1;
                        if depth > 64 {
                            return Err(format!("line {line}: comment nesting limit"));
                        }
                        p += 2;
                    } else if b[p..].starts_with(b"*/") {
                        depth -= 1;
                        p += 2;
                    } else {
                        if b[p] == b'\n' {
                            line += 1;
                        }
                        p += 1;
                    }
                }
                if depth > 0 {
                    return Err(format!("line {line}: unterminated comment"));
                }
            }
            b'\'' | b'"' | 96 => {
                let (quote, initial) = (b[p], line);
                p += 1;
                let from = p;
                while p < b.len() && b[p] != quote {
                    if b[p] == b'\\' {
                        p += 1;
                    }
                    if p < b.len() && b[p] == b'\n' {
                        line += 1;
                    }
                    p += 1;
                }
                if p == b.len() {
                    return Err(format!("line {initial}: unterminated literal"));
                }
                let value = s.get(from..p).ok_or("invalid UTF-8 literal boundary")?;
                if quote == 96 && value.contains('$') {
                    return Err(format!(
                        "line {initial}: template expressions require compiler-side review"
                    ));
                }
                out.push(Token {
                    kind: Kind::Literal,
                    text: value.into(),
                    line: initial,
                });
                p += 1;
            }
            c if c.is_ascii_alphabetic() || c == b'_' => {
                p += 1;
                while p < b.len() && (b[p].is_ascii_alphanumeric() || b[p] == b'_') {
                    p += 1;
                }
                out.push(Token {
                    kind: Kind::Name,
                    text: s[start..p].into(),
                    line,
                });
            }
            _ => {
                let c = s[p..].chars().next().ok_or("invalid UTF-8 boundary")?;
                p += c.len_utf8();
                out.push(Token {
                    kind: Kind::Symbol(c),
                    text: c.to_string(),
                    line,
                });
            }
        }
    }
    Ok(out)
}

fn inspect(source: &str) -> Result<(), String> {
    let tokens = lex(source)?;
    for (i, t) in tokens.iter().enumerate() {
        if t.name("java")
            && tokens
                .get(i + 1)
                .is_some_and(|n| n.symbol('{') || n.symbol(':'))
        {
            return Err(format!(
                "line {}: Java host interop forbidden in stdlib",
                t.line
            ));
        }
        if t.name("import") {
            let rest = &tokens[i + 1..];
            let size = rest
                .iter()
                .position(|x| x.symbol(';'))
                .ok_or_else(|| format!("line {}: import without terminator", t.line))?;
            if size > 128 {
                return Err(format!("line {}: oversized import", t.line));
            }
            let statement = &rest[..size];
            for (j, part) in statement.iter().enumerate() {
                if part.name("from") {
                    let src = &statement[j + 1..];
                    if src.is_empty() {
                        return Err(format!("line {}: missing import source", part.line));
                    }
                    let name = src.iter().map(|t| t.text.as_str()).collect::<String>();
                    if !name.starts_with("std/")
                        || name.contains("..")
                        || name.starts_with('/')
                        || name.contains(':')
                        || name.contains('\\')
                    {
                        return Err(format!("line {}: unsafe stdlib import source", part.line));
                    }
                }
            }
        }
    }
    Ok(())
}

fn scan(root: &Path) -> Result<usize, String> {
    if fs::symlink_metadata(root)
        .map_err(|e| e.to_string())?
        .file_type()
        .is_symlink()
    {
        return Err("stdlib root is a symlink".into());
    }
    let mut pending = vec![(root.to_path_buf(), 0)];
    let mut count = 0;
    while let Some((dir, depth)) = pending.pop() {
        if depth > 16 {
            return Err("stdlib nesting exceeds limit".into());
        }
        for entry in fs::read_dir(&dir).map_err(|e| e.to_string())? {
            let path = entry.map_err(|e| e.to_string())?.path();
            let meta = fs::symlink_metadata(&path).map_err(|e| e.to_string())?;
            if meta.file_type().is_symlink() {
                return Err(format!("{}: symlink refused", path.display()));
            }
            if meta.is_dir() {
                pending.push((path, depth + 1));
                continue;
            }
            if !meta.is_file() {
                return Err(format!("{}: unsupported entry", path.display()));
            }
            if path.extension().is_none_or(|e| e != "ores") {
                continue;
            }
            count += 1;
            if count > MAX_FILES || meta.len() > MAX_FILE {
                return Err(format!("{}: resource budget exceeded", path.display()));
            }
            let source = fs::read_to_string(&path).map_err(|e| e.to_string())?;
            inspect(&source).map_err(|e| format!("{}: {e}", path.display()))?;
        }
    }
    if count == 0 {
        return Err("stdlib contains no .ores files".into());
    }
    Ok(count)
}

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    if args.len() > 1 || args.first().is_some_and(|s| s.starts_with('-')) {
        eprintln!("usage: oreslang-stdlib-boundary-audit [stdlib-directory]");
        process::exit(2);
    }
    let root = args.first().map(String::as_str).unwrap_or("stdlib");
    match scan(Path::new(root)) {
        Ok(n) => println!("verified {n} native Oreslang stdlib source file(s)"),
        Err(e) => {
            eprintln!("stdlib boundary audit failed: {e}");
            process::exit(1);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn normal_module() {
        assert!(
            inspect("define module testing\npub fnc f(): String { return \"java:foo\"; } end")
                .is_ok()
        );
    }
    #[test]
    fn nested_comments() {
        assert!(inspect("// java { }\n/* java { /* nested */ } */ fnc f(): void {}").is_ok());
    }
    #[test]
    fn rejects_java_block() {
        assert!(inspect("do java { System.exit(0); }").is_err());
    }
    #[test]
    fn rejects_java_import() {
        assert!(inspect("import java:java.lang.System;").is_err());
    }
    #[test]
    fn rejects_parent_import() {
        assert!(inspect("import types Foo from '../../other';").is_err());
    }
    #[test]
    fn rejects_external_url() {
        assert!(inspect("import types Foo from 'https://elsewhere';").is_err());
    }
    #[test]
    fn rejects_unclosed_comment() {
        assert!(inspect("/* broken").is_err());
    }
    #[test]
    fn rejects_unclosed_string() {
        assert!(inspect("let s = 'broken").is_err());
    }
    #[test]
    fn rejects_unsafe_template() {
        assert!(inspect("let s = `$x`;").is_err());
    }
    #[test]
    fn rejects_ambient_import() {
        assert!(inspect("import module App from 'app/local';").is_err());
    }
    #[test]
    fn accepts_std_import() {
        assert!(inspect("import types Foo from 'std/core';").is_ok());
    }
    #[test]
    fn accepts_utf8_text() {
        assert!(inspect("let s = 'π';").is_ok());
    }
}
