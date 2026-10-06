//! Canonical, configuration-free formatting for Oreslang.
//!
//! The formatter deliberately has no style options. `format_source` is the SDK
//! entry point used by the CLI, editors, build tools, and language servers.

use std::fmt;

mod select;

const INDENT: &str = "  ";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FormatError {
    line: usize,
    message: String,
}

impl FormatError {
    fn new(line: usize, message: impl Into<String>) -> Self {
        Self {
            line,
            message: message.into(),
        }
    }

    pub fn line(&self) -> usize {
        self.line
    }

    pub fn message(&self) -> &str {
        &self.message
    }
}

impl fmt::Display for FormatError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "line {}: {}", self.line, self.message)
    }
}

impl std::error::Error for FormatError {}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum DefineKind {
    Module,
    Class,
    Interface,
    Trait,
    Struct,
    Other,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BraceKind {
    Callable,
    Module,
    Class,
    Interface,
    Trait,
    Struct,
    Actor,
    Other,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct BraceFrame {
    kind: BraceKind,
    indent: usize,
    order: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct DefineFrame {
    kind: DefineKind,
    order: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PrevEvent {
    Other,
    CallableEnd(usize),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum KeywordTerminator {
    FiKeyword,
    FiBraced,
    Done,
}

#[derive(Debug, Default, Clone, Copy)]
struct LexState {
    block_comment: bool,
    quote: Option<char>,
    escape: bool,
}

/// Format Oreslang source using the one canonical style.
///
/// The current formatter is intentionally configuration-free:
///
/// - two-space indentation, never tabs for indentation;
/// - braced static select arms, indented one level inside the select;
/// - LF line endings and exactly one final newline;
/// - trailing whitespace removed;
/// - at most one ordinary blank line;
/// - exactly two blank lines between sibling executable callable bodies;
/// - executable callable return separators are canonicalized to `->`;
/// - interface/trait callable signatures are canonicalized to `=>`;
/// - legacy scheduler spelling `rt yield` is canonicalized to `rt cooperate`;
/// - bare forever-loop aliases `for { ... }` / `for do ... done` are canonicalized to `loop`;
/// - `end`, `fi`, `done`, braces, class/interface/trait/struct/module nesting,
///   actor bodies, and strings/comments are indentation-aware.
///
/// It does not reorder declarations or rewrite tokens whose meaning depends on
/// the compiler's evolving grammar.
pub fn format_source(source: &str) -> Result<String, FormatError> {
    reject_multiline_literals(source)?;
    let normalized = source.replace("\r\n", "\n").replace('\r', "\n");
    let normalized = select::normalize(&normalized)?;
    let mut out: Vec<String> = Vec::new();
    let mut indent = 0usize;
    let mut pending_blank = false;
    let mut prev_event = PrevEvent::Other;
    let mut define_stack: Vec<DefineFrame> = Vec::new();
    let mut brace_stack: Vec<BraceFrame> = Vec::new();
    let mut keyword_stack: Vec<KeywordTerminator> = Vec::new();
    let mut lex = LexState::default();
    let mut next_order = 0usize;

    for (line_index, raw_line) in normalized.lines().enumerate() {
        let line_no = line_index + 1;
        let trimmed_end = raw_line.trim_end();
        if trimmed_end.trim().is_empty() {
            pending_blank = true;
            continue;
        }

        let mut content = trimmed_end.trim_start().to_string();
        content = canonicalize_conditional_line(&content, lex);
        content = canonicalize_forever_loop_line(&content, lex);
        content = canonicalize_rt_cooperate_line(&content, lex);
        let mut structural_lex = lex;
        let structural_content: String = scan_visible(&content, &mut structural_lex)
            .into_iter()
            .collect();
        let structural = structural_content.trim_start();

        let starts_end = starts_word(structural, "end");
        let starts_fi = starts_word(structural, "fi");
        let starts_done = starts_word(structural, "done");
        let branch_line = starts_word(structural, "else") || starts_word(structural, "elif");

        let fi_keyword_indented =
            starts_fi && matches!(keyword_stack.last(), Some(KeywordTerminator::FiKeyword));
        let branch_keyword_indented =
            branch_line && matches!(keyword_stack.last(), Some(KeywordTerminator::FiKeyword));

        if starts_fi {
            match keyword_stack.pop() {
                Some(KeywordTerminator::FiKeyword | KeywordTerminator::FiBraced) => {}
                Some(KeywordTerminator::Done) => {
                    return Err(FormatError::new(
                        line_no,
                        "encountered `fi` where `done` was expected",
                    ));
                }
                None => {
                    return Err(FormatError::new(
                        line_no,
                        "encountered `fi` without a matching `if ... then` block",
                    ));
                }
            }
        } else if starts_done {
            match keyword_stack.pop() {
                Some(KeywordTerminator::Done) => {}
                Some(KeywordTerminator::FiKeyword | KeywordTerminator::FiBraced) => {
                    return Err(FormatError::new(
                        line_no,
                        "encountered `done` where `fi` was expected",
                    ));
                }
                None => {
                    return Err(FormatError::new(
                        line_no,
                        "encountered `done` without a matching `... do` block",
                    ));
                }
            }
        }

        if branch_line
            && !matches!(
                keyword_stack.last(),
                Some(KeywordTerminator::FiKeyword | KeywordTerminator::FiBraced)
            )
        {
            return Err(FormatError::new(
                line_no,
                "encountered `else`/`elif` without a matching `if ... then` block",
            ));
        }

        let leading_closing_braces = leading_closing_braces(structural);
        let keyword_dedent = usize::from(
            starts_end || starts_done || fi_keyword_indented || branch_keyword_indented,
        );
        let pre_dedent = leading_closing_braces + keyword_dedent;
        indent = indent.saturating_sub(pre_dedent);

        if starts_end {
            define_stack.pop().ok_or_else(|| {
                FormatError::new(line_no, "encountered `end` without a matching define block")
            })?;
        }

        let signature_context = nearest_signature_context(&define_stack, &brace_stack);
        let callable_body = is_callable_body_header(structural, signature_context);
        let callable_signature = is_callable_signature(structural, signature_context);
        if callable_body {
            content = canonicalize_callable_arrow(&content, "->", lex);
        } else if callable_signature {
            content = canonicalize_callable_arrow(&content, "=>", lex);
        }

        let current_indent = indent;
        let annotation_line = structural.starts_with('@');
        let desired_blanks = if out.is_empty() || is_closer_line(structural) {
            0
        } else if (callable_body || annotation_line)
            && matches!(prev_event, PrevEvent::CallableEnd(i) if i == current_indent)
        {
            2
        } else if pending_blank {
            1
        } else {
            0
        };
        push_blank_lines(&mut out, desired_blanks);
        pending_blank = false;

        out.push(format!("{}{}", INDENT.repeat(current_indent), content));

        let brace_events: Vec<char> = structural
            .chars()
            .filter(|c| matches!(c, '{' | '}'))
            .collect();
        lex = structural_lex;

        let opens = brace_events.iter().filter(|&&c| c == '{').count();
        let closes = brace_events.iter().filter(|&&c| c == '}').count();
        let mut callable_open_available = callable_body;
        let mut container_open_available = brace_container_kind(structural);
        let mut ended_callable = None;
        for event in brace_events {
            match event {
                '{' => {
                    let kind = if callable_open_available {
                        callable_open_available = false;
                        BraceKind::Callable
                    } else if let Some(kind) = container_open_available.take() {
                        kind
                    } else {
                        BraceKind::Other
                    };
                    next_order += 1;
                    brace_stack.push(BraceFrame {
                        kind,
                        indent: current_indent,
                        order: next_order,
                    });
                }
                '}' => {
                    let frame = brace_stack.pop().ok_or_else(|| {
                        FormatError::new(line_no, "encountered `}` without a matching `{`")
                    })?;
                    if frame.kind == BraceKind::Callable {
                        ended_callable = Some(frame.indent);
                    }
                }
                _ => unreachable!(),
            }
        }

        if let Some(ended_indent) = ended_callable {
            prev_event = PrevEvent::CallableEnd(ended_indent);
        } else {
            prev_event = PrevEvent::Other;
        }

        let nonleading_closes = closes.saturating_sub(leading_closing_braces);
        if opens >= nonleading_closes {
            indent += opens - nonleading_closes;
        } else {
            indent = indent.saturating_sub(nonleading_closes - opens);
        }

        if let Some(kind) = define_kind(structural)
            && !structural.contains('{')
            && !ends_statement(structural)
        {
            next_order += 1;
            define_stack.push(DefineFrame {
                kind,
                order: next_order,
            });
            indent += 1;
        }

        if opens_conditional_block(structural) && !branch_line {
            keyword_stack.push(KeywordTerminator::FiKeyword);
            indent += 1;
        } else if starts_word(structural, "if") && structural.contains('{') {
            keyword_stack.push(KeywordTerminator::FiBraced);
        } else if opens_do_block(structural) && !branch_line {
            keyword_stack.push(KeywordTerminator::Done);
            indent += 1;
        }

        if branch_line && branch_keyword_indented {
            indent += 1;
        }
    }

    if lex.block_comment || lex.quote.is_some() {
        return Err(FormatError::new(
            normalized.lines().count().max(1),
            "unterminated string or block comment",
        ));
    }
    if !brace_stack.is_empty() {
        return Err(FormatError::new(
            normalized.lines().count().max(1),
            "unterminated `{` block",
        ));
    }
    if !define_stack.is_empty() {
        return Err(FormatError::new(
            normalized.lines().count().max(1),
            "unterminated define block",
        ));
    }
    if let Some(terminator) = keyword_stack.last() {
        let message = match terminator {
            KeywordTerminator::FiKeyword | KeywordTerminator::FiBraced => {
                "unterminated `if` block (expected `fi`)"
            }
            KeywordTerminator::Done => "unterminated `... do` block (expected `done`)",
        };
        return Err(FormatError::new(normalized.lines().count().max(1), message));
    }

    while out.last().is_some_and(|line| line.is_empty()) {
        out.pop();
    }

    let mut result = out.join("\n");
    result.push('\n');
    Ok(result)
}

/// Returns `true` when `source` is already in canonical form.
pub fn is_formatted(source: &str) -> Result<bool, FormatError> {
    Ok(format_source(source)? == source)
}

fn push_blank_lines(out: &mut Vec<String>, count: usize) {
    while out.last().is_some_and(|line| line.is_empty()) {
        out.pop();
    }
    for _ in 0..count {
        out.push(String::new());
    }
}

fn starts_word(line: &str, word: &str) -> bool {
    if !line.starts_with(word) {
        return false;
    }
    line[word.len()..]
        .chars()
        .next()
        .is_none_or(|c| !is_ident_char(c))
}

fn is_ident_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || c == '_'
}

fn leading_closing_braces(line: &str) -> usize {
    line.chars()
        .take_while(|c| c.is_whitespace() || *c == '}')
        .filter(|c| *c == '}')
        .count()
}

fn is_closer_line(line: &str) -> bool {
    line.starts_with('}')
        || starts_word(line, "end")
        || starts_word(line, "fi")
        || starts_word(line, "done")
}

fn ends_statement(line: &str) -> bool {
    line.trim_end().ends_with(';')
}

fn nearest_signature_context(defines: &[DefineFrame], braces: &[BraceFrame]) -> bool {
    let mut newest: Option<(usize, bool)> = None;

    for frame in defines {
        let is_signature = matches!(frame.kind, DefineKind::Interface | DefineKind::Trait);
        if newest.is_none_or(|(order, _)| frame.order > order) {
            newest = Some((frame.order, is_signature));
        }
    }

    for frame in braces {
        let is_signature = match frame.kind {
            BraceKind::Interface | BraceKind::Trait => Some(true),
            BraceKind::Callable
            | BraceKind::Module
            | BraceKind::Class
            | BraceKind::Struct
            | BraceKind::Actor => Some(false),
            BraceKind::Other => None,
        };
        if let Some(is_signature) = is_signature
            && newest.is_none_or(|(order, _)| frame.order > order)
        {
            newest = Some((frame.order, is_signature));
        }
    }

    newest
        .map(|(_, is_signature)| is_signature)
        .unwrap_or(false)
}

fn define_kind(line: &str) -> Option<DefineKind> {
    let mut words = line.split_whitespace();
    if words.next()? != "define" {
        return None;
    }
    Some(match words.next()? {
        "module" => DefineKind::Module,
        "class" => DefineKind::Class,
        "interface" => DefineKind::Interface,
        "trait" => DefineKind::Trait,
        "struct" => DefineKind::Struct,
        _ => DefineKind::Other,
    })
}

fn brace_container_kind(line: &str) -> Option<BraceKind> {
    let prefix = line.split('{').next()?;
    let words: Vec<&str> = prefix
        .split(|c: char| !is_ident_char(c))
        .filter(|word| !word.is_empty())
        .collect();
    if words.contains(&"interface") {
        Some(BraceKind::Interface)
    } else if words.contains(&"trait") {
        Some(BraceKind::Trait)
    } else if words.contains(&"class") {
        Some(BraceKind::Class)
    } else if words.contains(&"module") {
        Some(BraceKind::Module)
    } else if words.contains(&"struct") {
        Some(BraceKind::Struct)
    } else if words.contains(&"actor") {
        Some(BraceKind::Actor)
    } else {
        None
    }
}

fn is_callable_body_header(structural: &str, signature_context: bool) -> bool {
    if !structural.contains('{') || !structural.contains('(') {
        return false;
    }
    let lower = structural.trim_start();
    for kw in [
        "if", "for", "while", "switch", "match", "catch", "recover", "defer", "case", "default",
    ] {
        if starts_word(lower, kw) {
            return false;
        }
    }
    if lower.starts_with("obj{") || lower.starts_with("arr[") {
        return false;
    }
    if contains_word(lower, "fnc") || contains_word(lower, "routine") {
        return true;
    }
    // Methods omit `fnc`. A braced callable inside interface/trait is a default
    // implementation and therefore still uses executable `->` syntax.
    if signature_context
        || structural.contains("self")
        || structural.contains(") ->")
        || structural.contains("): ")
        || structural.contains(") =>")
    {
        return true;
    }
    // Class/actor method shape, including `[Symbol.iterator]()`.
    let prefix = lower.split('(').next().unwrap_or("").trim();
    !prefix.contains('=') && !prefix.contains('.') && !prefix.ends_with("new")
}

fn is_callable_signature(structural: &str, signature_context: bool) -> bool {
    signature_context
        && structural.trim_end().ends_with(';')
        && structural.contains('(')
        && !structural.contains('{')
}

fn contains_word(line: &str, word: &str) -> bool {
    line.split(|c: char| !is_ident_char(c))
        .any(|part| part == word)
}

fn canonicalize_callable_arrow(line: &str, desired: &str, initial: LexState) -> String {
    let body_at = first_visible_char_index(line, '{', initial)
        .or_else(|| line.rfind(';'))
        .unwrap_or(line.len());
    let Some(close_paren) = callable_parameter_close(line, body_at, initial) else {
        return line.to_string();
    };
    let tail = &line[close_paren + 1..body_at];
    let trimmed = tail.trim_start();
    let leading_ws = tail.len() - trimmed.len();
    let sep_len = if trimmed.starts_with("=>") || trimmed.starts_with("->") {
        2
    } else if trimmed.starts_with(':') {
        1
    } else {
        return line.to_string();
    };

    let absolute = close_paren + 1 + leading_ws;
    let before = line[..absolute].trim_end();
    let after = line[absolute + sep_len..].trim_start();
    format!("{before} {desired} {after}")
}

fn callable_parameter_close(line: &str, limit: usize, initial: LexState) -> Option<usize> {
    let chars: Vec<(usize, char)> = line[..limit].char_indices().collect();
    let mut state = initial;
    let mut depth = 0usize;
    let mut saw_open = false;
    let mut i = 0usize;

    while i < chars.len() {
        let (index, ch) = chars[i];
        let next = chars.get(i + 1).map(|(_, c)| *c);

        if state.block_comment {
            if ch == '*' && next == Some('/') {
                state.block_comment = false;
                i += 2;
            } else {
                i += 1;
            }
            continue;
        }

        if let Some(q) = state.quote {
            if state.escape {
                state.escape = false;
            } else if ch == '\\' {
                state.escape = true;
            } else if ch == q {
                state.quote = None;
            }
            i += 1;
            continue;
        }

        if ch == '/' && next == Some('/') {
            break;
        }
        if ch == '/' && next == Some('*') {
            state.block_comment = true;
            i += 2;
            continue;
        }
        if matches!(ch, '"' | '\'' | '`') {
            state.quote = Some(ch);
            i += 1;
            continue;
        }

        match ch {
            '(' => {
                depth += 1;
                saw_open = true;
            }
            ')' if saw_open => {
                depth = depth.saturating_sub(1);
                if depth == 0 {
                    return Some(index);
                }
            }
            _ => {}
        }
        i += 1;
    }

    None
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct WordSpan {
    start: usize,
    end: usize,
}

fn canonicalize_conditional_line(line: &str, initial: LexState) -> String {
    let words = visible_word_spans(line, initial);
    if words.is_empty() {
        return line.to_string();
    }

    let word = |span: WordSpan| &line[span.start..span.end];
    let first = word(words[0]);

    let mut replacements: Vec<(usize, usize, String)> = Vec::new();
    let conditional = if first == "if" {
        true
    } else if first == "elseif" {
        replacements.push((words[0].start, words[0].end, "elif".to_string()));
        true
    } else if first == "elif" {
        true
    } else if first == "else" && words.get(1).is_some_and(|span| word(*span) == "if") {
        replacements.push((words[0].start, words[1].end, "elif".to_string()));
        true
    } else {
        false
    };

    if !conditional {
        return line.to_string();
    }

    // Braced conditionals do not use then/do. We still canonicalize branch
    // aliases above so all source converges on "elif".
    if first_visible_char_index(line, '{', initial).is_none()
        && let Some(last) = words.last().copied()
    {
        let last_word = word(last);
        if last_word == "do" || last_word == "then" {
            let prefix = &line[..last.start];
            let before = prefix.trim_end();
            let replacement_start = before.len();
            let replacement = if before.ends_with(';') {
                " then".to_string()
            } else {
                "; then".to_string()
            };
            replacements.push((replacement_start, last.end, replacement));
        }
    }

    if replacements.is_empty() {
        return line.to_string();
    }

    replacements.sort_by_key(|(start, _, _)| *start);
    for pair in replacements.windows(2) {
        debug_assert!(
            pair[0].1 <= pair[1].0,
            "conditional rewrites must not overlap"
        );
    }

    let mut result = line.to_string();
    for (start, end, replacement) in replacements.into_iter().rev() {
        result.replace_range(start..end, &replacement);
    }
    result
}

fn canonicalize_forever_loop_line(line: &str, initial: LexState) -> String {
    let words = visible_word_spans(line, initial);
    let Some(first) = words.first().copied() else {
        return line.to_string();
    };
    if &line[first.start..first.end] != "for" {
        return line.to_string();
    }

    // Decide from visible syntax, not raw bytes, so comments cannot trick the
    // formatter into rewriting a conventional or iterator for-loop.
    let mut state = initial;
    let visible: String = scan_visible(line, &mut state).into_iter().collect();
    let trimmed = visible.trim_start();
    if !starts_word(trimmed, "for") {
        return line.to_string();
    }
    let rest = trimmed["for".len()..].trim_start();
    let bare_braced = rest.starts_with('{');
    let bare_keyword = starts_word(rest, "do") && rest["do".len()..].trim().is_empty();
    if !bare_braced && !bare_keyword {
        return line.to_string();
    }

    let mut result = line.to_string();
    result.replace_range(first.start..first.end, "loop");
    result
}

fn canonicalize_rt_cooperate_line(line: &str, initial: LexState) -> String {
    let words = visible_word_spans(line, initial);
    if words.len() < 2 {
        return line.to_string();
    }

    let first = &line[words[0].start..words[0].end];
    let second = &line[words[1].start..words[1].end];
    if first != "rt" || second != "yield" {
        return line.to_string();
    }

    // Rewrite only the runtime namespace operation token. Generator
    // `yield value` remains untouched, as do strings/comments containing
    // the compatibility spelling.
    let mut result = line.to_string();
    result.replace_range(words[1].start..words[1].end, "cooperate");
    result
}

fn visible_word_spans(line: &str, initial: LexState) -> Vec<WordSpan> {
    let chars: Vec<(usize, char)> = line.char_indices().collect();
    let mut words = Vec::new();
    let mut state = initial;
    let mut i = 0usize;

    while i < chars.len() {
        let (start, c) = chars[i];
        let next = chars.get(i + 1).map(|(_, c)| *c);

        if state.block_comment {
            if c == '*' && next == Some('/') {
                state.block_comment = false;
                i += 2;
            } else {
                i += 1;
            }
            continue;
        }

        if let Some(q) = state.quote {
            if state.escape {
                state.escape = false;
            } else if c == '\\' {
                state.escape = true;
            } else if c == q {
                state.quote = None;
            }
            i += 1;
            continue;
        }

        if c == '/' && next == Some('/') {
            break;
        }
        if c == '/' && next == Some('*') {
            state.block_comment = true;
            i += 2;
            continue;
        }
        if matches!(c, '"' | '\'' | '`') {
            state.quote = Some(c);
            i += 1;
            continue;
        }

        if is_ident_char(c) {
            let mut end = start + c.len_utf8();
            i += 1;
            while i < chars.len() && is_ident_char(chars[i].1) {
                end = chars[i].0 + chars[i].1.len_utf8();
                i += 1;
            }
            words.push(WordSpan { start, end });
            continue;
        }

        i += 1;
    }

    words
}

fn opens_conditional_block(line: &str) -> bool {
    let t = line.trim_end();
    starts_word(t.trim_start(), "if")
        && (t.ends_with(" then")
            || t.ends_with("; then")
            || t.ends_with(" do")
            || t.ends_with("; do"))
}

fn opens_do_block(line: &str) -> bool {
    let t = line.trim_end();
    (t.ends_with(" do") || t.ends_with("; do")) && !starts_word(t.trim_start(), "done")
}

fn reject_multiline_literals(source: &str) -> Result<(), FormatError> {
    let lines: Vec<&str> = source.split('\n').collect();
    let mut state = LexState::default();

    for (index, line) in lines.iter().enumerate() {
        let logical = line.strip_suffix('\r').unwrap_or(line);
        let mut next = state;
        scan_visible(logical, &mut next);

        if index + 1 < lines.len() && next.quote.is_some() {
            return Err(FormatError::new(
                index + 1,
                "multiline string/template literals are not formatted yet; refusing to change literal bytes",
            ));
        }

        state = next;
    }

    Ok(())
}

fn first_visible_char_index(line: &str, needle: char, initial: LexState) -> Option<usize> {
    let chars: Vec<(usize, char)> = line.char_indices().collect();
    let mut state = initial;
    let mut i = 0usize;

    while i < chars.len() {
        let (byte_index, c) = chars[i];
        let next = chars.get(i + 1).map(|(_, c)| *c);

        if state.block_comment {
            if c == '*' && next == Some('/') {
                state.block_comment = false;
                i += 2;
            } else {
                i += 1;
            }
            continue;
        }

        if let Some(q) = state.quote {
            if state.escape {
                state.escape = false;
            } else if c == '\\' {
                state.escape = true;
            } else if c == q {
                state.quote = None;
            }
            i += 1;
            continue;
        }

        if c == '/' && next == Some('/') {
            return None;
        }
        if c == '/' && next == Some('*') {
            state.block_comment = true;
            i += 2;
            continue;
        }
        if matches!(c, '"' | '\'' | '`') {
            state.quote = Some(c);
            i += 1;
            continue;
        }
        if c == needle {
            return Some(byte_index);
        }

        i += 1;
    }

    None
}

fn scan_visible(line: &str, state: &mut LexState) -> Vec<char> {
    let chars: Vec<char> = line.chars().collect();
    let mut visible = Vec::new();
    let mut i = 0usize;

    while i < chars.len() {
        let c = chars[i];
        let next = chars.get(i + 1).copied();

        if state.block_comment {
            if c == '*' && next == Some('/') {
                state.block_comment = false;
                visible.push(' ');
                i += 2;
            } else {
                i += 1;
            }
            continue;
        }

        if let Some(q) = state.quote {
            if state.escape {
                state.escape = false;
            } else if c == '\\' {
                state.escape = true;
            } else if c == q {
                state.quote = None;
            }
            i += 1;
            continue;
        }

        if c == '/' && next == Some('/') {
            break;
        }
        if c == '/' && next == Some('*') {
            visible.push(' ');
            state.block_comment = true;
            i += 2;
            continue;
        }
        if matches!(c, '"' | '\'' | '`') {
            visible.push(' ');
            state.quote = Some(c);
            i += 1;
            continue;
        }

        visible.push(c);
        i += 1;
    }

    visible
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonicalizes_rt_yield_without_touching_generator_yield_or_literals() {
        let src = r#"fnc generator* values(): Iterator<int> {
  rt yield;
  rt yield();
  yield 1;
  yield* values();
  stdio.stdout.write("rt yield");
  // rt yield
  return;
}
"#;

        let got = format_source(src).unwrap();
        assert!(got.contains("rt cooperate;"));
        assert!(got.contains("rt cooperate();"));
        assert!(got.contains("yield 1;"));
        assert!(got.contains("yield* values();"));
        assert!(got.contains("\"rt yield\""));
        assert!(got.contains("// rt yield"));
        assert!(!got.contains("  rt yield;"));
        assert!(!got.contains("  rt yield();"));
    }

    #[test]
    fn canonicalizes_executable_and_signature_arrows() {
        let src = r#"define interface Named
  fnc name(): String;
end

define trait Retryable
  fnc retry() -> bool;
end

define class User implements Named, Retryable as
  pub name() => String {
    return "u";
  }
end
"#;
        let got = format_source(src).unwrap();
        assert!(got.contains("fnc name() => String;"));
        assert!(got.contains("fnc retry() => bool;"));
        assert!(got.contains("pub name() -> String {"));
    }
}
