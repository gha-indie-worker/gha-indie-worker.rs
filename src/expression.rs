//! Bounded, typed evaluation for the trusted GitHub Actions expression subset.
//!
//! The evaluator follows GitHub's documented literal, coercion, comparison,
//! logical-operator, property/index, and pure-function behavior. Callers provide
//! an explicit context map, so unavailable or sensitive contexts cannot become
//! ambient authority. Parsing and generated values are bounded before workflow
//! data can consume excessive memory.

use std::collections::{BTreeMap, BTreeSet};
use std::error::Error;
use std::fmt::{Display, Formatter};

use serde_json::{Number, Value};

pub const EXPRESSION_SCHEMA_VERSION: &str = "gha-indie-worker.expression.v1";
pub const MAX_EXPRESSION_SOURCE_BYTES: usize = 4 * 1024;
pub const MAX_EXPRESSION_TOKENS: usize = 512;
pub const MAX_EXPRESSION_DEPTH: usize = 64;
pub const MAX_FUNCTION_ARGUMENTS: usize = 32;
pub const MAX_EXPRESSION_VALUE_BYTES: usize = 64 * 1024;

#[derive(Debug, Clone, Copy, Default, Eq, PartialEq)]
pub struct StatusContext {
    pub success: bool,
    pub failure: bool,
    pub cancelled: bool,
}

#[derive(Debug, Clone, Default)]
pub struct ExpressionContext {
    roots: BTreeMap<String, Value>,
    status: Option<StatusContext>,
}

impl ExpressionContext {
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    #[must_use]
    pub fn with_root(mut self, name: impl Into<String>, value: Value) -> Self {
        self.roots.insert(name.into(), value);
        self
    }

    #[must_use]
    pub fn with_status(mut self, status: StatusContext) -> Self {
        self.status = Some(status);
        self
    }

    fn allowed_roots(&self) -> BTreeSet<&str> {
        self.roots.keys().map(String::as_str).collect()
    }
}

#[derive(Debug, Clone, Eq, PartialEq)]
pub struct ExpressionError {
    pub code: &'static str,
    pub message: String,
}

impl ExpressionError {
    fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

impl Display for ExpressionError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "{}: {}", self.code, self.message)
    }
}

impl Error for ExpressionError {}

#[derive(Debug, Clone, PartialEq)]
enum Token {
    Identifier(String),
    String(String),
    Number(String),
    True,
    False,
    Null,
    LeftParenthesis,
    RightParenthesis,
    LeftBracket,
    RightBracket,
    Comma,
    Dot,
    Not,
    Less,
    LessOrEqual,
    Greater,
    GreaterOrEqual,
    Equal,
    NotEqual,
    And,
    Or,
    End,
}

#[derive(Debug, Clone)]
enum Expression {
    Literal(Value),
    Root(String),
    Property(Box<Self>, String),
    Index(Box<Self>, Box<Self>),
    Call(String, Vec<Self>),
    Not(Box<Self>),
    Binary(BinaryOperator, Box<Self>, Box<Self>),
}

#[derive(Debug, Clone, Copy)]
enum BinaryOperator {
    Less,
    LessOrEqual,
    Greater,
    GreaterOrEqual,
    Equal,
    NotEqual,
    And,
    Or,
}

/// Parses and evaluates one expression using only the explicitly supplied
/// contexts.
///
/// # Errors
///
/// Returns a bounded syntax, context, function, coercion, or value error.
pub fn evaluate_expression(
    source: &str,
    context: &ExpressionContext,
) -> Result<Value, ExpressionError> {
    let expression = parse_expression(source)?;
    validate_tree(
        &expression,
        &context.allowed_roots(),
        context.status.is_some(),
        0,
    )?;
    let value = evaluate_tree(&expression, context, 0)?;
    bound_value(&value)?;
    Ok(value)
}

/// Evaluates an expression and applies GitHub Actions conditional truthiness.
///
/// # Errors
///
/// Returns the same bounded errors as [`evaluate_expression`].
pub fn evaluate_condition(
    source: &str,
    context: &ExpressionContext,
) -> Result<bool, ExpressionError> {
    evaluate_expression(source, context).map(|value| is_truthy(&value))
}

/// Validates an expression without resolving context values. This is used by
/// executor preflight so every step is checked before arbitrary code starts.
///
/// # Errors
///
/// Returns a syntax or unsupported-context/function error.
pub fn validate_expression(
    source: &str,
    allowed_roots: &[&str],
    allow_status_functions: bool,
) -> Result<(), ExpressionError> {
    let expression = parse_expression(source)?;
    let allowed_roots = allowed_roots.iter().copied().collect::<BTreeSet<_>>();
    validate_tree(&expression, &allowed_roots, allow_status_functions, 0)
}

/// Reports whether an expression explicitly invokes any status-check function.
/// GitHub implicitly prepends `success()` to step conditions that do not.
///
/// # Errors
///
/// Returns a bounded syntax error when the expression cannot be parsed.
pub fn uses_status_function(source: &str) -> Result<bool, ExpressionError> {
    parse_expression(source).map(|expression| tree_uses_status_function(&expression))
}

/// Resolves every `${{ ... }}` segment in a string and converts scalar results
/// using GitHub's documented expression-to-string rules.
///
/// # Errors
///
/// Returns a parse, evaluation, unsupported-value, or output-bound error.
pub fn render_template(
    source: &str,
    context: &ExpressionContext,
) -> Result<String, ExpressionError> {
    let mut rest = source;
    let mut rendered = String::with_capacity(source.len());
    while let Some(start) = rest.find("${{") {
        rendered.push_str(&rest[..start]);
        let expression_start = start + 3;
        let end = expression_end(rest, expression_start)?;
        let expression = rest[expression_start..end].trim();
        let value = evaluate_expression(expression, context)?;
        rendered.push_str(&value_to_string(&value)?);
        if rendered.len() > MAX_EXPRESSION_VALUE_BYTES {
            return Err(ExpressionError::new(
                "expression_value_too_large",
                format!("rendered expression exceeds {MAX_EXPRESSION_VALUE_BYTES} bytes"),
            ));
        }
        rest = &rest[end + 2..];
    }
    rendered.push_str(rest);
    if rendered.len() > MAX_EXPRESSION_VALUE_BYTES {
        return Err(ExpressionError::new(
            "expression_value_too_large",
            format!("rendered expression exceeds {MAX_EXPRESSION_VALUE_BYTES} bytes"),
        ));
    }
    Ok(rendered)
}

/// Validates every expression segment in a template without evaluating it.
///
/// # Errors
///
/// Returns a syntax or unsupported-context/function error.
pub fn validate_template(
    source: &str,
    allowed_roots: &[&str],
    allow_status_functions: bool,
) -> Result<(), ExpressionError> {
    let mut rest = source;
    while let Some(start) = rest.find("${{") {
        let expression_start = start + 3;
        let end = expression_end(rest, expression_start)?;
        validate_expression(
            rest[expression_start..end].trim(),
            allowed_roots,
            allow_status_functions,
        )?;
        rest = &rest[end + 2..];
    }
    Ok(())
}

/// Evaluates a string that consists solely of one `${{ ... }}` expression.
/// Returns `None` when the source contains literal text around the expression.
///
/// # Errors
///
/// Returns a parse or evaluation error for a wrapped expression.
pub fn evaluate_wrapped_expression(
    source: &str,
    context: &ExpressionContext,
) -> Result<Option<Value>, ExpressionError> {
    let trimmed = source.trim();
    if !trimmed.starts_with("${{") {
        return Ok(None);
    }
    let end = expression_end(trimmed, 3)?;
    if end + 2 != trimmed.len() {
        return Ok(None);
    }
    evaluate_expression(trimmed[3..end].trim(), context).map(Some)
}

#[must_use]
pub fn is_truthy(value: &Value) -> bool {
    match value {
        Value::Null => false,
        Value::Bool(value) => *value,
        Value::Number(value) => value.as_f64().is_some_and(|number| number != 0.0),
        Value::String(value) => !value.is_empty(),
        Value::Array(_) | Value::Object(_) => true,
    }
}

/// Converts a scalar with GitHub's documented expression string coercion.
/// Arrays and objects deliberately fail instead of being silently serialized.
///
/// # Errors
///
/// Returns `non_scalar_expression_value` for arrays and objects.
pub fn value_to_string(value: &Value) -> Result<String, ExpressionError> {
    match value {
        Value::Null => Ok(String::new()),
        Value::Bool(value) => Ok(value.to_string()),
        Value::Number(value) => Ok(value.to_string()),
        Value::String(value) => Ok(value.clone()),
        Value::Array(_) | Value::Object(_) => Err(ExpressionError::new(
            "non_scalar_expression_value",
            "arrays and objects require toJSON() before string interpolation",
        )),
    }
}

fn expression_end(source: &str, start: usize) -> Result<usize, ExpressionError> {
    let bytes = source.as_bytes();
    let mut index = start;
    let mut quoted = false;
    while index < bytes.len() {
        match bytes[index] {
            b'\'' => {
                if quoted && bytes.get(index + 1) == Some(&b'\'') {
                    index += 2;
                    continue;
                }
                quoted = !quoted;
                index += 1;
            }
            b'}' if !quoted && bytes.get(index + 1) == Some(&b'}') => return Ok(index),
            _ => index += 1,
        }
    }
    Err(ExpressionError::new(
        "invalid_expression",
        "expression is missing its closing braces",
    ))
}

fn parse_expression(source: &str) -> Result<Expression, ExpressionError> {
    if source.len() > MAX_EXPRESSION_SOURCE_BYTES {
        return Err(ExpressionError::new(
            "expression_too_large",
            format!(
                "expression contains {} bytes; maximum is {MAX_EXPRESSION_SOURCE_BYTES}",
                source.len()
            ),
        ));
    }
    let tokens = tokenize(source)?;
    let mut parser = Parser { tokens, index: 0 };
    let expression = parser.parse_or(0)?;
    if !matches!(parser.peek(), Token::End) {
        return Err(ExpressionError::new(
            "invalid_expression",
            format!("unexpected token {:?} after expression", parser.peek()),
        ));
    }
    Ok(expression)
}

fn tokenize(source: &str) -> Result<Vec<Token>, ExpressionError> {
    let characters = source.chars().collect::<Vec<_>>();
    let mut tokens = Vec::new();
    let mut index = 0;
    while index < characters.len() {
        let character = characters[index];
        if character.is_whitespace() {
            index += 1;
            continue;
        }
        let token = match character {
            '(' => {
                index += 1;
                Token::LeftParenthesis
            }
            ')' => {
                index += 1;
                Token::RightParenthesis
            }
            '[' => {
                index += 1;
                Token::LeftBracket
            }
            ']' => {
                index += 1;
                Token::RightBracket
            }
            ',' => {
                index += 1;
                Token::Comma
            }
            '.' if characters.get(index + 1).is_some_and(char::is_ascii_digit) => {
                let (number, next) = scan_number(&characters, index)?;
                index = next;
                Token::Number(number)
            }
            '.' => {
                index += 1;
                Token::Dot
            }
            '!' if characters.get(index + 1) == Some(&'=') => {
                index += 2;
                Token::NotEqual
            }
            '!' => {
                index += 1;
                Token::Not
            }
            '<' if characters.get(index + 1) == Some(&'=') => {
                index += 2;
                Token::LessOrEqual
            }
            '<' => {
                index += 1;
                Token::Less
            }
            '>' if characters.get(index + 1) == Some(&'=') => {
                index += 2;
                Token::GreaterOrEqual
            }
            '>' => {
                index += 1;
                Token::Greater
            }
            '=' if characters.get(index + 1) == Some(&'=') => {
                index += 2;
                Token::Equal
            }
            '&' if characters.get(index + 1) == Some(&'&') => {
                index += 2;
                Token::And
            }
            '|' if characters.get(index + 1) == Some(&'|') => {
                index += 2;
                Token::Or
            }
            '\'' => {
                let (value, next) = scan_string(&characters, index)?;
                index = next;
                Token::String(value)
            }
            '"' => {
                return Err(ExpressionError::new(
                    "invalid_expression",
                    "expression string literals must use single quotes",
                ));
            }
            '-' if characters.get(index + 1).is_some_and(char::is_ascii_digit) => {
                let (number, next) = scan_number(&characters, index)?;
                index = next;
                Token::Number(number)
            }
            digit if digit.is_ascii_digit() => {
                let (number, next) = scan_number(&characters, index)?;
                index = next;
                Token::Number(number)
            }
            first if first.is_ascii_alphabetic() || first == '_' => {
                let start = index;
                index += 1;
                while characters
                    .get(index)
                    .is_some_and(|next| next.is_ascii_alphanumeric() || matches!(next, '_' | '-'))
                {
                    index += 1;
                }
                let value = characters[start..index].iter().collect::<String>();
                match value.as_str() {
                    "true" => Token::True,
                    "false" => Token::False,
                    "null" => Token::Null,
                    _ => Token::Identifier(value),
                }
            }
            other => {
                return Err(ExpressionError::new(
                    "invalid_expression",
                    format!("unexpected character {other:?} in expression"),
                ));
            }
        };
        tokens.push(token);
        if tokens.len() > MAX_EXPRESSION_TOKENS {
            return Err(ExpressionError::new(
                "expression_too_complex",
                format!("expression exceeds {MAX_EXPRESSION_TOKENS} tokens"),
            ));
        }
    }
    tokens.push(Token::End);
    Ok(tokens)
}

fn scan_string(characters: &[char], start: usize) -> Result<(String, usize), ExpressionError> {
    let mut value = String::new();
    let mut index = start + 1;
    while index < characters.len() {
        if characters[index] == '\'' {
            if characters.get(index + 1) == Some(&'\'') {
                value.push('\'');
                index += 2;
                continue;
            }
            return Ok((value, index + 1));
        }
        value.push(characters[index]);
        index += 1;
    }
    Err(ExpressionError::new(
        "invalid_expression",
        "unterminated single-quoted string literal",
    ))
}

fn scan_number(characters: &[char], start: usize) -> Result<(String, usize), ExpressionError> {
    let mut index = start;
    if characters.get(index) == Some(&'-') {
        index += 1;
    }
    if characters.get(index) == Some(&'0')
        && characters
            .get(index + 1)
            .is_some_and(|value| matches!(value, 'x' | 'X'))
    {
        index += 2;
        let digits = index;
        while characters.get(index).is_some_and(char::is_ascii_hexdigit) {
            index += 1;
        }
        if index == digits {
            return Err(ExpressionError::new(
                "invalid_expression",
                "hexadecimal literal contains no digits",
            ));
        }
        return Ok((characters[start..index].iter().collect(), index));
    }

    let mut saw_digit = false;
    while characters.get(index).is_some_and(char::is_ascii_digit) {
        saw_digit = true;
        index += 1;
    }
    if characters.get(index) == Some(&'.') {
        index += 1;
        while characters.get(index).is_some_and(char::is_ascii_digit) {
            saw_digit = true;
            index += 1;
        }
    }
    if !saw_digit {
        return Err(ExpressionError::new(
            "invalid_expression",
            "numeric literal contains no digits",
        ));
    }
    if characters
        .get(index)
        .is_some_and(|value| matches!(value, 'e' | 'E'))
    {
        index += 1;
        if characters
            .get(index)
            .is_some_and(|value| matches!(value, '+' | '-'))
        {
            index += 1;
        }
        let exponent = index;
        while characters.get(index).is_some_and(char::is_ascii_digit) {
            index += 1;
        }
        if index == exponent {
            return Err(ExpressionError::new(
                "invalid_expression",
                "numeric exponent contains no digits",
            ));
        }
    }
    Ok((characters[start..index].iter().collect(), index))
}

struct Parser {
    tokens: Vec<Token>,
    index: usize,
}

impl Parser {
    fn peek(&self) -> &Token {
        self.tokens.get(self.index).unwrap_or(&Token::End)
    }

    fn take(&mut self) -> Token {
        let token = self.peek().clone();
        self.index = self.index.saturating_add(1);
        token
    }

    fn parse_or(&mut self, depth: usize) -> Result<Expression, ExpressionError> {
        self.ensure_depth(depth)?;
        let mut left = self.parse_and(depth + 1)?;
        while matches!(self.peek(), Token::Or) {
            self.take();
            let right = self.parse_and(depth + 1)?;
            left = Expression::Binary(BinaryOperator::Or, Box::new(left), Box::new(right));
        }
        Ok(left)
    }

    fn parse_and(&mut self, depth: usize) -> Result<Expression, ExpressionError> {
        self.ensure_depth(depth)?;
        let mut left = self.parse_comparison(depth + 1)?;
        while matches!(self.peek(), Token::And) {
            self.take();
            let right = self.parse_comparison(depth + 1)?;
            left = Expression::Binary(BinaryOperator::And, Box::new(left), Box::new(right));
        }
        Ok(left)
    }

    fn parse_comparison(&mut self, depth: usize) -> Result<Expression, ExpressionError> {
        self.ensure_depth(depth)?;
        let mut left = self.parse_unary(depth + 1)?;
        loop {
            let operator = match self.peek() {
                Token::Less => BinaryOperator::Less,
                Token::LessOrEqual => BinaryOperator::LessOrEqual,
                Token::Greater => BinaryOperator::Greater,
                Token::GreaterOrEqual => BinaryOperator::GreaterOrEqual,
                Token::Equal => BinaryOperator::Equal,
                Token::NotEqual => BinaryOperator::NotEqual,
                _ => break,
            };
            self.take();
            let right = self.parse_unary(depth + 1)?;
            left = Expression::Binary(operator, Box::new(left), Box::new(right));
        }
        Ok(left)
    }

    fn parse_unary(&mut self, depth: usize) -> Result<Expression, ExpressionError> {
        self.ensure_depth(depth)?;
        if matches!(self.peek(), Token::Not) {
            self.take();
            return Ok(Expression::Not(Box::new(self.parse_unary(depth + 1)?)));
        }
        self.parse_postfix(depth + 1)
    }

    fn parse_postfix(&mut self, depth: usize) -> Result<Expression, ExpressionError> {
        self.ensure_depth(depth)?;
        let mut expression = self.parse_primary(depth + 1)?;
        loop {
            match self.peek() {
                Token::Dot => {
                    self.take();
                    let Token::Identifier(property) = self.take() else {
                        return Err(ExpressionError::new(
                            "invalid_expression",
                            "property dereference requires an identifier",
                        ));
                    };
                    expression = Expression::Property(Box::new(expression), property);
                }
                Token::LeftBracket => {
                    self.take();
                    let index = self.parse_or(depth + 1)?;
                    if !matches!(self.take(), Token::RightBracket) {
                        return Err(ExpressionError::new(
                            "invalid_expression",
                            "index expression is missing its closing bracket",
                        ));
                    }
                    expression = Expression::Index(Box::new(expression), Box::new(index));
                }
                _ => break,
            }
        }
        Ok(expression)
    }

    fn parse_primary(&mut self, depth: usize) -> Result<Expression, ExpressionError> {
        self.ensure_depth(depth)?;
        match self.take() {
            Token::String(value) => Ok(Expression::Literal(Value::String(value))),
            Token::Number(value) => parse_number(&value).map(Expression::Literal),
            Token::True => Ok(Expression::Literal(Value::Bool(true))),
            Token::False => Ok(Expression::Literal(Value::Bool(false))),
            Token::Null => Ok(Expression::Literal(Value::Null)),
            Token::Identifier(name) if matches!(self.peek(), Token::LeftParenthesis) => {
                self.take();
                let mut arguments = Vec::new();
                if !matches!(self.peek(), Token::RightParenthesis) {
                    loop {
                        arguments.push(self.parse_or(depth + 1)?);
                        if arguments.len() > MAX_FUNCTION_ARGUMENTS {
                            return Err(ExpressionError::new(
                                "expression_too_complex",
                                format!("function exceeds {MAX_FUNCTION_ARGUMENTS} arguments"),
                            ));
                        }
                        if !matches!(self.peek(), Token::Comma) {
                            break;
                        }
                        self.take();
                    }
                }
                if !matches!(self.take(), Token::RightParenthesis) {
                    return Err(ExpressionError::new(
                        "invalid_expression",
                        format!("function {name:?} is missing its closing parenthesis"),
                    ));
                }
                Ok(Expression::Call(name, arguments))
            }
            Token::Identifier(name) => Ok(Expression::Root(name)),
            Token::LeftParenthesis => {
                let expression = self.parse_or(depth + 1)?;
                if !matches!(self.take(), Token::RightParenthesis) {
                    return Err(ExpressionError::new(
                        "invalid_expression",
                        "group is missing its closing parenthesis",
                    ));
                }
                Ok(expression)
            }
            token => Err(ExpressionError::new(
                "invalid_expression",
                format!("unexpected token {token:?}"),
            )),
        }
    }

    fn ensure_depth(&self, depth: usize) -> Result<(), ExpressionError> {
        if depth > MAX_EXPRESSION_DEPTH {
            Err(ExpressionError::new(
                "expression_too_complex",
                format!("expression exceeds nesting depth {MAX_EXPRESSION_DEPTH}"),
            ))
        } else {
            Ok(())
        }
    }
}

fn parse_number(source: &str) -> Result<Value, ExpressionError> {
    let negative = source.starts_with('-');
    let unsigned = source.strip_prefix('-').unwrap_or(source);
    if let Some(hex) = unsigned
        .strip_prefix("0x")
        .or_else(|| unsigned.strip_prefix("0X"))
    {
        let value = u64::from_str_radix(hex, 16).map_err(|_| {
            ExpressionError::new(
                "invalid_expression",
                format!("invalid hexadecimal literal {source:?}"),
            )
        })?;
        if negative {
            let signed = i64::try_from(value)
                .ok()
                .and_then(i64::checked_neg)
                .ok_or_else(|| {
                    ExpressionError::new(
                        "invalid_expression",
                        format!("numeric literal {source:?} is outside the supported range"),
                    )
                })?;
            return Ok(Value::Number(Number::from(signed)));
        }
        return Ok(Value::Number(Number::from(value)));
    }
    serde_json::from_str::<Value>(source)
        .ok()
        .filter(Value::is_number)
        .ok_or_else(|| {
            ExpressionError::new(
                "invalid_expression",
                format!("invalid numeric literal {source:?}"),
            )
        })
}

fn validate_tree(
    expression: &Expression,
    allowed_roots: &BTreeSet<&str>,
    allow_status_functions: bool,
    depth: usize,
) -> Result<(), ExpressionError> {
    if depth > MAX_EXPRESSION_DEPTH {
        return Err(ExpressionError::new(
            "expression_too_complex",
            format!("expression exceeds nesting depth {MAX_EXPRESSION_DEPTH}"),
        ));
    }
    match expression {
        Expression::Literal(_) => Ok(()),
        Expression::Root(name) => {
            if allowed_roots.contains(name.as_str()) {
                Ok(())
            } else {
                Err(ExpressionError::new(
                    "unsupported_context",
                    format!("context {name:?} is unavailable in this execution boundary"),
                ))
            }
        }
        Expression::Property(parent, _) => {
            validate_tree(parent, allowed_roots, allow_status_functions, depth + 1)
        }
        Expression::Index(parent, index) => {
            validate_tree(parent, allowed_roots, allow_status_functions, depth + 1)?;
            validate_tree(index, allowed_roots, allow_status_functions, depth + 1)
        }
        Expression::Not(value) => {
            validate_tree(value, allowed_roots, allow_status_functions, depth + 1)
        }
        Expression::Binary(_, left, right) => {
            validate_tree(left, allowed_roots, allow_status_functions, depth + 1)?;
            validate_tree(right, allowed_roots, allow_status_functions, depth + 1)
        }
        Expression::Call(name, arguments) => {
            let normalized = name.to_ascii_lowercase();
            let expected = match normalized.as_str() {
                "contains" | "startswith" | "endswith" => 2..=2,
                "join" => 1..=2,
                "tojson" | "fromjson" => 1..=1,
                "format" => 1..=MAX_FUNCTION_ARGUMENTS,
                "success" | "failure" | "cancelled" | "always" => {
                    if !allow_status_functions {
                        return Err(ExpressionError::new(
                            "unsupported_status_function",
                            format!("status function {name}() is unavailable here"),
                        ));
                    }
                    0..=0
                }
                _ => {
                    return Err(ExpressionError::new(
                        "unsupported_function",
                        format!("function {name:?} is outside the expression v1 subset"),
                    ));
                }
            };
            if !expected.contains(&arguments.len()) {
                return Err(ExpressionError::new(
                    "invalid_function_arguments",
                    format!(
                        "function {name:?} received {} arguments; expected {}..={}",
                        arguments.len(),
                        expected.start(),
                        expected.end()
                    ),
                ));
            }
            for argument in arguments {
                validate_tree(argument, allowed_roots, allow_status_functions, depth + 1)?;
            }
            Ok(())
        }
    }
}

fn tree_uses_status_function(expression: &Expression) -> bool {
    match expression {
        Expression::Literal(_) | Expression::Root(_) => false,
        Expression::Property(parent, _) | Expression::Not(parent) => {
            tree_uses_status_function(parent)
        }
        Expression::Index(parent, index) | Expression::Binary(_, parent, index) => {
            tree_uses_status_function(parent) || tree_uses_status_function(index)
        }
        Expression::Call(name, arguments) => {
            matches!(
                name.to_ascii_lowercase().as_str(),
                "success" | "failure" | "cancelled" | "always"
            ) || arguments.iter().any(tree_uses_status_function)
        }
    }
}

fn evaluate_tree(
    expression: &Expression,
    context: &ExpressionContext,
    depth: usize,
) -> Result<Value, ExpressionError> {
    if depth > MAX_EXPRESSION_DEPTH {
        return Err(ExpressionError::new(
            "expression_too_complex",
            format!("expression exceeds nesting depth {MAX_EXPRESSION_DEPTH}"),
        ));
    }
    match expression {
        Expression::Literal(value) => Ok(value.clone()),
        Expression::Root(name) => context.roots.get(name).cloned().ok_or_else(|| {
            ExpressionError::new(
                "unsupported_context",
                format!("context {name:?} is unavailable in this execution boundary"),
            )
        }),
        Expression::Property(parent, property) => {
            let parent = evaluate_tree(parent, context, depth + 1)?;
            Ok(match parent {
                Value::Object(values) => values.get(property).cloned().unwrap_or(Value::Null),
                _ => Value::Null,
            })
        }
        Expression::Index(parent, index) => {
            let parent = evaluate_tree(parent, context, depth + 1)?;
            let index = evaluate_tree(index, context, depth + 1)?;
            Ok(match (parent, index) {
                (Value::Object(values), Value::String(key)) => {
                    values.get(&key).cloned().unwrap_or(Value::Null)
                }
                (Value::Array(values), Value::Number(index)) => index
                    .as_u64()
                    .and_then(|index| usize::try_from(index).ok())
                    .and_then(|index| values.get(index).cloned())
                    .unwrap_or(Value::Null),
                _ => Value::Null,
            })
        }
        Expression::Not(value) => Ok(Value::Bool(!is_truthy(&evaluate_tree(
            value,
            context,
            depth + 1,
        )?))),
        Expression::Binary(BinaryOperator::And, left, right) => {
            let left = evaluate_tree(left, context, depth + 1)?;
            if is_truthy(&left) {
                evaluate_tree(right, context, depth + 1)
            } else {
                Ok(left)
            }
        }
        Expression::Binary(BinaryOperator::Or, left, right) => {
            let left = evaluate_tree(left, context, depth + 1)?;
            if is_truthy(&left) {
                Ok(left)
            } else {
                evaluate_tree(right, context, depth + 1)
            }
        }
        Expression::Binary(operator, left, right) => {
            let left = evaluate_tree(left, context, depth + 1)?;
            let right = evaluate_tree(right, context, depth + 1)?;
            Ok(Value::Bool(compare_values(*operator, &left, &right)))
        }
        Expression::Call(name, arguments) => evaluate_function(name, arguments, context, depth + 1),
    }
}

fn compare_values(operator: BinaryOperator, left: &Value, right: &Value) -> bool {
    match operator {
        BinaryOperator::Equal => loose_equal(left, right),
        BinaryOperator::NotEqual => !loose_equal(left, right),
        BinaryOperator::Less
        | BinaryOperator::LessOrEqual
        | BinaryOperator::Greater
        | BinaryOperator::GreaterOrEqual => {
            if let (Value::String(left), Value::String(right)) = (left, right) {
                let ordering = left.to_ascii_lowercase().cmp(&right.to_ascii_lowercase());
                return match operator {
                    BinaryOperator::Less => ordering.is_lt(),
                    BinaryOperator::LessOrEqual => ordering.is_le(),
                    BinaryOperator::Greater => ordering.is_gt(),
                    BinaryOperator::GreaterOrEqual => ordering.is_ge(),
                    _ => false,
                };
            }
            let (Some(left), Some(right)) = (to_number(left), to_number(right)) else {
                return false;
            };
            match operator {
                BinaryOperator::Less => left < right,
                BinaryOperator::LessOrEqual => left <= right,
                BinaryOperator::Greater => left > right,
                BinaryOperator::GreaterOrEqual => left >= right,
                _ => false,
            }
        }
        BinaryOperator::And | BinaryOperator::Or => false,
    }
}

fn loose_equal(left: &Value, right: &Value) -> bool {
    match (left, right) {
        (Value::Null, Value::Null) => true,
        (Value::Bool(left), Value::Bool(right)) => left == right,
        (Value::Number(left), Value::Number(right)) => left.as_f64() == right.as_f64(),
        (Value::String(left), Value::String(right)) => left.eq_ignore_ascii_case(right),
        (Value::Array(_), Value::Array(_)) | (Value::Object(_), Value::Object(_)) => false,
        _ => {
            matches!((to_number(left), to_number(right)), (Some(left), Some(right)) if left == right)
        }
    }
}

fn to_number(value: &Value) -> Option<f64> {
    match value {
        Value::Null => Some(0.0),
        Value::Bool(false) => Some(0.0),
        Value::Bool(true) => Some(1.0),
        Value::Number(value) => value.as_f64(),
        Value::String(value) if value.trim().is_empty() => Some(0.0),
        Value::String(value) => serde_json::from_str::<Value>(value.trim())
            .ok()
            .and_then(|value| value.as_f64()),
        Value::Array(_) | Value::Object(_) => None,
    }
}

fn evaluate_function(
    name: &str,
    arguments: &[Expression],
    context: &ExpressionContext,
    depth: usize,
) -> Result<Value, ExpressionError> {
    let normalized = name.to_ascii_lowercase();
    match normalized.as_str() {
        "success" => Ok(Value::Bool(
            context.status.is_some_and(|status| status.success),
        )),
        "failure" => Ok(Value::Bool(
            context.status.is_some_and(|status| status.failure),
        )),
        "cancelled" => Ok(Value::Bool(
            context.status.is_some_and(|status| status.cancelled),
        )),
        "always" => Ok(Value::Bool(true)),
        "contains" => {
            let search = evaluate_tree(&arguments[0], context, depth + 1)?;
            let item = evaluate_tree(&arguments[1], context, depth + 1)?;
            let contains = match search {
                Value::Array(values) => values.iter().any(|value| loose_equal(value, &item)),
                search => string_for_function(&search)?
                    .to_ascii_lowercase()
                    .contains(&string_for_function(&item)?.to_ascii_lowercase()),
            };
            Ok(Value::Bool(contains))
        }
        "startswith" | "endswith" => {
            let search = string_for_function(&evaluate_tree(&arguments[0], context, depth + 1)?)?
                .to_ascii_lowercase();
            let value = string_for_function(&evaluate_tree(&arguments[1], context, depth + 1)?)?
                .to_ascii_lowercase();
            Ok(Value::Bool(if normalized == "startswith" {
                search.starts_with(&value)
            } else {
                search.ends_with(&value)
            }))
        }
        "join" => {
            let value = evaluate_tree(&arguments[0], context, depth + 1)?;
            let separator = if let Some(separator) = arguments.get(1) {
                string_for_function(&evaluate_tree(separator, context, depth + 1)?)?
            } else {
                ",".to_string()
            };
            let joined = match value {
                Value::Array(values) => values
                    .iter()
                    .map(string_for_function)
                    .collect::<Result<Vec<_>, _>>()?
                    .join(&separator),
                Value::String(value) => value,
                other => string_for_function(&other)?,
            };
            Ok(Value::String(joined))
        }
        "tojson" => {
            let value = evaluate_tree(&arguments[0], context, depth + 1)?;
            serde_json::to_string_pretty(&value)
                .map(Value::String)
                .map_err(|error| ExpressionError::new("expression_json_error", error.to_string()))
        }
        "fromjson" => {
            let source = string_for_function(&evaluate_tree(&arguments[0], context, depth + 1)?)?;
            if source.len() > MAX_EXPRESSION_VALUE_BYTES {
                return Err(ExpressionError::new(
                    "expression_value_too_large",
                    format!("fromJSON input exceeds {MAX_EXPRESSION_VALUE_BYTES} bytes"),
                ));
            }
            serde_json::from_str(&source).map_err(|error| {
                ExpressionError::new(
                    "invalid_json_expression",
                    format!("fromJSON input is invalid JSON: {error}"),
                )
            })
        }
        "format" => {
            let template = string_for_function(&evaluate_tree(&arguments[0], context, depth + 1)?)?;
            let replacements = arguments[1..]
                .iter()
                .map(|argument| {
                    evaluate_tree(argument, context, depth + 1)
                        .and_then(|value| string_for_function(&value))
                })
                .collect::<Result<Vec<_>, _>>()?;
            format_expression(&template, &replacements).map(Value::String)
        }
        _ => Err(ExpressionError::new(
            "unsupported_function",
            format!("function {name:?} is outside the expression v1 subset"),
        )),
    }
}

fn string_for_function(value: &Value) -> Result<String, ExpressionError> {
    value_to_string(value).map_err(|_| {
        ExpressionError::new(
            "invalid_function_value",
            "function cannot coerce an array or object to a string",
        )
    })
}

fn format_expression(template: &str, replacements: &[String]) -> Result<String, ExpressionError> {
    let characters = template.chars().collect::<Vec<_>>();
    let mut rendered = String::with_capacity(template.len());
    let mut index = 0;
    while index < characters.len() {
        match characters[index] {
            '{' if characters.get(index + 1) == Some(&'{') => {
                rendered.push('{');
                index += 2;
            }
            '}' if characters.get(index + 1) == Some(&'}') => {
                rendered.push('}');
                index += 2;
            }
            '{' => {
                let start = index + 1;
                let mut end = start;
                while characters.get(end).is_some_and(char::is_ascii_digit) {
                    end += 1;
                }
                if end == start || characters.get(end) != Some(&'}') {
                    return Err(ExpressionError::new(
                        "invalid_format_expression",
                        "format placeholder must be a numeric index such as {0}",
                    ));
                }
                let replacement_index = characters[start..end]
                    .iter()
                    .collect::<String>()
                    .parse::<usize>()
                    .map_err(|_| {
                        ExpressionError::new(
                            "invalid_format_expression",
                            "format placeholder index is invalid",
                        )
                    })?;
                let replacement = replacements.get(replacement_index).ok_or_else(|| {
                    ExpressionError::new(
                        "invalid_format_expression",
                        format!("format replacement {{{replacement_index}}} is unavailable"),
                    )
                })?;
                rendered.push_str(replacement);
                index = end + 1;
            }
            '}' => {
                return Err(ExpressionError::new(
                    "invalid_format_expression",
                    "format string contains an unmatched closing brace",
                ));
            }
            character => {
                rendered.push(character);
                index += 1;
            }
        }
        if rendered.len() > MAX_EXPRESSION_VALUE_BYTES {
            return Err(ExpressionError::new(
                "expression_value_too_large",
                format!("format result exceeds {MAX_EXPRESSION_VALUE_BYTES} bytes"),
            ));
        }
    }
    Ok(rendered)
}

fn bound_value(value: &Value) -> Result<(), ExpressionError> {
    let bytes = serde_json::to_vec(value)
        .map_err(|error| ExpressionError::new("expression_json_error", error.to_string()))?;
    if bytes.len() > MAX_EXPRESSION_VALUE_BYTES {
        Err(ExpressionError::new(
            "expression_value_too_large",
            format!(
                "evaluated value contains {} bytes; maximum is {MAX_EXPRESSION_VALUE_BYTES}",
                bytes.len()
            ),
        ))
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn context() -> ExpressionContext {
        ExpressionContext::new()
            .with_root(
                "matrix",
                json!({"enabled": true, "word": "Alpha", "missing": null}),
            )
            .with_root("env", json!({"COUNT": "7", "FLAG": "true"}))
            .with_root(
                "steps",
                json!({
                    "producer": {
                        "outputs": {"count": "7", "word": "HeLLo"},
                        "outcome": "success",
                        "conclusion": "success"
                    }
                }),
            )
            .with_status(StatusContext {
                success: true,
                failure: false,
                cancelled: false,
            })
    }

    #[test]
    fn evaluates_typed_literals_operators_and_contexts() {
        let source = r#"
            success() && matrix.enabled &&
            env.COUNT == 7 &&
            steps.producer.outputs.count >= 7 &&
            steps['producer'].outputs.word == 'hello' &&
            matrix.unknown == '' &&
            0xff == 255 && -2.5e1 < -20
        "#;
        assert!(evaluate_condition(source, &context()).unwrap());
        assert_eq!(
            evaluate_expression("null == false", &context()).unwrap(),
            json!(true)
        );
        assert_eq!(
            evaluate_expression("'9' > 8", &context()).unwrap(),
            json!(true)
        );
        assert_eq!(
            evaluate_expression("'abc' > 1", &context()).unwrap(),
            json!(false)
        );
        assert_eq!(evaluate_expression("!0", &context()).unwrap(), json!(true));
    }

    #[test]
    fn evaluates_documented_functions_and_short_circuit_values() {
        let source = r#"
            contains(fromJSON('["push","pull_request"]'), 'PUSH') &&
            startsWith(matrix.word, 'al') &&
            endsWith(steps.producer.outputs.word, 'LO')
        "#;
        assert!(evaluate_condition(source, &context()).unwrap());
        assert_eq!(
            evaluate_expression("join(fromJSON('[\"a\",\"b\"]'), '-')", &context()).unwrap(),
            json!("a-b")
        );
        assert_eq!(
            evaluate_expression("format('{{{0}}}:{1}', 'x', true)", &context()).unwrap(),
            json!("{x}:true")
        );
        assert_eq!(
            evaluate_expression("false && fromJSON('bad') || 'fallback'", &context()).unwrap(),
            json!("fallback")
        );
        assert_eq!(
            evaluate_expression("fromJSON('[0,1]')[1]", &context()).unwrap(),
            json!(1)
        );
    }

    #[test]
    fn renders_templates_and_preserves_wrapped_types() {
        assert_eq!(
            render_template(
                "${{ matrix.word }}:${{ format('{0}', env.COUNT) }}",
                &context()
            )
            .unwrap(),
            "Alpha:7"
        );
        assert_eq!(
            evaluate_wrapped_expression("${{ fromJSON(env.FLAG) }}", &context()).unwrap(),
            Some(json!(true))
        );
        assert_eq!(
            evaluate_wrapped_expression("prefix-${{ env.FLAG }}", &context()).unwrap(),
            None
        );
    }

    #[test]
    fn rejects_unavailable_sensitive_contexts_and_unsupported_functions() {
        let secret = evaluate_expression("secrets.TOKEN", &context()).unwrap_err();
        assert_eq!(secret.code, "unsupported_context");
        let github = evaluate_expression("github.token", &context()).unwrap_err();
        assert_eq!(github.code, "unsupported_context");
        let hash = evaluate_expression("hashFiles('**/Cargo.lock')", &context()).unwrap_err();
        assert_eq!(hash.code, "unsupported_function");
        let status = validate_expression("success()", &["env"], false).unwrap_err();
        assert_eq!(status.code, "unsupported_status_function");
    }

    #[test]
    fn rejects_malformed_or_excessive_expressions() {
        assert_eq!(
            evaluate_expression("\"double quoted\"", &context())
                .unwrap_err()
                .code,
            "invalid_expression"
        );
        assert_eq!(
            evaluate_expression("format('{2}', 'only')", &context())
                .unwrap_err()
                .code,
            "invalid_format_expression"
        );
        let oversized = "x".repeat(MAX_EXPRESSION_SOURCE_BYTES + 1);
        assert_eq!(
            evaluate_expression(&oversized, &context())
                .unwrap_err()
                .code,
            "expression_too_large"
        );
    }

    #[test]
    fn detects_nested_status_functions_for_implicit_success_semantics() {
        assert!(!uses_status_function("matrix.enabled").unwrap());
        assert!(uses_status_function("matrix.enabled || failure()").unwrap());
        assert!(uses_status_function("!cancelled() && env.FLAG").unwrap());
    }
}
