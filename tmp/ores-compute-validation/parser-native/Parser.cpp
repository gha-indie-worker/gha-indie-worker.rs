#include "oreslang/frontend/Parser.hpp"
#include "oreslang/types/OwnershipChecker.hpp"

#include <algorithm>
#include <charconv>
#include <functional>
#include <cctype>
#include <stdexcept>
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <utility>

namespace oreslang {
namespace {

enum class Kind { Identifier, Integer, Symbol, End };
struct Token {
    Kind kind;
    std::string_view text;
    std::size_t offset;
};

bool exceptional_control(std::string_view word) {
    return word == "throw" || word == "raise" || word == "panic" ||
           word == "recover" || word == "defer" || word == "try" ||
           word == "catch" || word == "finally" || word == "trap";
}

bool digit(char ch) { return ch >= '0' && ch <= '9'; }
bool identifier_start(char ch) {
    return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch == '_';
}
bool identifier_part(char ch) { return identifier_start(ch) || digit(ch); }

bool reserved(std::string_view name) {
    // Keep these reserved even while their grammar is unsupported.
    static constexpr std::string_view words[] = {
        "define", "class", "enum", "pattern", "module", "contract", "conforms", "namespace",
        "import", "from", "as", "extends", "implements", "try", "catch",
        "throw", "raise", "panic", "recover",
        "finally", "end", "fi", "if", "do", "else", "then", "new",
        "spawn", "stop", "done", "await", "rt", "async", "suspends", "suspend", "critical", "generator",
        "nlex", "trap", "actor", "isoactor", "def", "fnc", "routine",
        "while", "for", "of", "loop", "block", "break", "continue", "yield",
        "super", "elseif", "elif", "switch", "match", "matches", "is",
        "default", "eq", "neq", "nb", "select", "readch", "writech",
        "cb", "shared", "untrusted", "type", "types", "typeof",
        "interface", "trait", "struct", "impl", "abstract", "void",
        "static", "pub", "private", "return", "defer", "throw", "raise",
        "panic", "recover", "val", "const",
        "let", "mut", "borrow", "self", "true", "false", "null", "obj", "arr",
        "gpu", "cpu", "quantum", "over", "structural", "int", "bool", "pure",
        "using", "requires", "serial", "critical-read", "critical-write", "nb-select"
    };
    return std::find(std::begin(words), std::end(words), name) != std::end(words);
}

class Lexer {
public:
    explicit Lexer(std::string_view source) : source_(source) {}

    Token next() {
        for (;;) {
            if (at_ == source_.size()) return {Kind::End, {}, at_};
            const unsigned char ch = static_cast<unsigned char>(source_[at_]);
            if (std::isspace(ch)) { ++at_; continue; }
            if (source_.substr(at_, 2) == "//") {
                const auto end = source_.find('\n', at_ + 2);
                at_ = end == std::string_view::npos ? source_.size() : end;
                continue;
            }
            if (source_.substr(at_, 2) == "/*") {
                const auto start = at_;
                at_ += 2;
                std::size_t depth = 1;
                while (depth != 0) {
                    if (at_ == source_.size()) {
                        throw std::runtime_error("unterminated block comment at byte "
                                                 + std::to_string(start));
                    }
                    if (source_.substr(at_, 2) == "/*") { at_ += 2; ++depth; }
                    else if (source_.substr(at_, 2) == "*/") { at_ += 2; --depth; }
                    else ++at_;
                }
                continue;
            }
            break;
        }
        const std::size_t start = at_;
        const char ch = source_[at_];
        if (identifier_start(ch)) {
            ++at_;
            while (at_ < source_.size() && identifier_part(source_[at_])) ++at_;
            // Keep 'critical-read' / 'critical-write' as ordinary
            // IDENT '-' IDENT tokens. The parser recognizes them *only*
            // in callable modifier positions: arbitrary expressions must
            // never lose subtraction because of declaration syntax.
            // Existing nb-select recognition is intentionally unchanged
            // until native select lowering supports that grammar.
            const auto first = source_.substr(start, at_ - start);
            if (first == "nb" && source_.substr(at_, 7) == "-select") {
                const auto end = at_ + 7;
                if (end == source_.size() ||
                        (source_[end] != '-' && !identifier_part(source_[end]))) {
                    at_ = end;
                }
            }
            return {Kind::Identifier, source_.substr(start, at_ - start), start};
        }
        if (digit(ch)) {
            ++at_;
            while (at_ < source_.size() && (digit(source_[at_]) || source_[at_] == '_')) {
                ++at_;
            }
            return {Kind::Integer, source_.substr(start, at_ - start), start};
        }
        // Contiguous comparison/logical operators are single tokens, matching
        // the Java/Graal precedence grammar.
        for (std::string_view op : {"==", "!=", "<=", ">=", "&&", "||", "^^"}) {
            if (source_.substr(at_, 2) == op) {
                at_ += 2;
                return {Kind::Symbol, source_.substr(start, 2), start};
            }
        }
        if (ch == '@' && source_.substr(start, 15) == "@DefaultArgVals") {
            throw std::runtime_error("native @DefaultArgVals declaration requires "
                                     "checked named defaults, ownership and ABI lowering at byte "
                                     + std::to_string(start));
        }
        // Admit '@' as a TOKEN only so existing parameter-local @Default guards
        // can issue their specific fail-closed diagnostic instead of a generic
        // unsupported-token error. No annotation execution is enabled.
        if (std::string_view("(){},:;+-*/%=.<>@?!").find(ch) != std::string_view::npos) {
            ++at_;
            return {Kind::Symbol, source_.substr(start, 1), start};
        }
        throw std::runtime_error("unsupported Oreslang token at byte "
                                 + std::to_string(start));
    }

private:
    std::string_view source_;
    std::size_t at_ = 0;
};

std::unique_ptr<Expression> make_expression(Expression::Kind kind,
                                             std::size_t offset) {
    auto result = std::make_unique<Expression>();
    result->kind = kind;
    result->offset = offset;
    return result;
}

inline constexpr std::size_t kMaxNativeExpressionDepth = 64;

inline void require_expression_depth(std::size_t depth, std::size_t offset) {
    if (depth > kMaxNativeExpressionDepth) {
        throw std::runtime_error(
            "native expression nesting limit exceeded (64) at byte " +
            std::to_string(offset));
    }
}

// The first native terminal-try slice has no exceptional CFG. Restrict
// protected return/guard expressions to primitive values or an addressable,
// ownership-checked class field read: no nested calls or ownership transfers.
bool nonthrowing_protected_read(const Expression& expr) {
    if (expr.kind == Expression::Kind::Integer ||
            expr.kind == Expression::Kind::Boolean ||
            expr.kind == Expression::Kind::Variable) return true;
    return expr.kind == Expression::Kind::Field && expr.left &&
           expr.left->kind == Expression::Kind::Variable;
}

// Arithmetic over checked scalar reads is nonthrowing. Never admit
// general calls, array mutations, ownership transfers, or suspension.
bool native_pure_scalar_tree(const Expression& expr, std::size_t depth = 1) {
    require_expression_depth(depth, expr.offset);
    if (nonthrowing_protected_read(expr)) return true;
    if (expr.kind == Expression::Kind::Negate ||
            expr.kind == Expression::Kind::LogicalNot)
        return expr.left && native_pure_scalar_tree(*expr.left, depth + 1);
    if (expr.kind == Expression::Kind::Add ||
            expr.kind == Expression::Kind::Subtract ||
            expr.kind == Expression::Kind::Multiply ||
            expr.kind == Expression::Kind::Equal ||
            expr.kind == Expression::Kind::NotEqual ||
            expr.kind == Expression::Kind::Less ||
            expr.kind == Expression::Kind::LessEqual ||
            expr.kind == Expression::Kind::Greater ||
            expr.kind == Expression::Kind::GreaterEqual ||
            expr.kind == Expression::Kind::LogicalAnd ||
            expr.kind == Expression::Kind::LogicalOr ||
            expr.kind == Expression::Kind::LogicalXor)
        return expr.left && expr.right &&
            native_pure_scalar_tree(*expr.left, depth + 1) &&
            native_pure_scalar_tree(*expr.right, depth + 1);
    if (expr.kind == Expression::Kind::Conditional)
        return (expr.condition ||
                (expr.condition_lhs && expr.condition_rhs)) &&
            expr.when_true && expr.when_false &&
            (!expr.condition || native_pure_scalar_tree(*expr.condition, depth + 1)) &&
            (!expr.condition_lhs || native_pure_scalar_tree(*expr.condition_lhs, depth + 1)) &&
            (!expr.condition_rhs || native_pure_scalar_tree(*expr.condition_rhs, depth + 1)) &&
            native_pure_scalar_tree(*expr.when_true, depth + 1) &&
            native_pure_scalar_tree(*expr.when_false, depth + 1);
    return false;
}

struct NativeCallSignature {
    std::vector<std::string> parameters;
    std::vector<GenericParameter> generics;
    std::vector<std::string> parameter_generic_types;
    std::vector<ScalarType> parameter_scalar_types;
    std::vector<bool> parameter_is_scalar;
    ScalarType return_type = ScalarType::I64;
    bool returns_void = false;
};
using NativeSignatures = std::unordered_map<
    std::string, std::unordered_map<std::size_t, NativeCallSignature>>;

// This proof is deliberately narrower than the general native scalar guard:
// an unpublished constructor can read only integer literals and its own
// explicitly typed scalar parameters, never arbitrary fields or operations.
bool native_constructor_scalar_tree(const Expression& expr,
                                    std::size_t depth = 1) {
    require_expression_depth(depth, expr.offset);
    using Kind = Expression::Kind;
    if (expr.kind == Kind::Integer || expr.kind == Kind::Boolean ||
            expr.kind == Kind::Variable) return true;
    if (expr.kind == Kind::Negate || expr.kind == Kind::LogicalNot)
        return expr.left && native_constructor_scalar_tree(*expr.left, depth + 1);
    if (expr.kind == Kind::Add || expr.kind == Kind::Subtract ||
            expr.kind == Kind::Multiply || expr.kind == Kind::Equal ||
            expr.kind == Kind::NotEqual || expr.kind == Kind::Less ||
            expr.kind == Kind::LessEqual || expr.kind == Kind::Greater ||
            expr.kind == Kind::GreaterEqual || expr.kind == Kind::LogicalAnd ||
            expr.kind == Kind::LogicalOr || expr.kind == Kind::LogicalXor)
        return expr.left && expr.right &&
            native_constructor_scalar_tree(*expr.left, depth + 1) &&
            native_constructor_scalar_tree(*expr.right, depth + 1);
    if (expr.kind == Kind::Conditional)
        return (expr.condition || (expr.condition_lhs && expr.condition_rhs)) &&
            expr.when_true && expr.when_false &&
            (!expr.condition || native_constructor_scalar_tree(*expr.condition, depth + 1)) &&
            (!expr.condition_lhs || native_constructor_scalar_tree(*expr.condition_lhs, depth + 1)) &&
            (!expr.condition_rhs || native_constructor_scalar_tree(*expr.condition_rhs, depth + 1)) &&
            native_constructor_scalar_tree(*expr.when_true, depth + 1) &&
            native_constructor_scalar_tree(*expr.when_false, depth + 1);
    return false;
}

// A runtime callable's code/signature must never be forged as a nominal
// object or actor identity in the constrained native frontend.
bool callable_nominal_name(std::string_view name) {
    return name == "Callable" || name == "Fnc" || name == "Function"
            || name == "Routine" || name == "Method";
}

// Entry is a compiler-owned nominal product in the Java/Graal frontend.
// Until the native backend has a real two-slot representation, projection
// ownership, value equality, transport traits and ABI tests, it must not be
// forgeable as an ordinary user class/actor nominal.
bool entry_builtin_name(std::string_view name) {
    return name == "Entry";
}

[[noreturn]] void reject_native_entry_boundary(std::size_t offset) {
    throw std::runtime_error(
        "native Entry<K,V> requires nominal product lowering and ownership parity"
        " at byte " + std::to_string(offset));
}

class Parser {
public:
    explicit Parser(std::string_view source, bool experimental_owned_int,
                    bool experimental_rt_scalar_loop)
        : source_(source), lexer_(source), token_(lexer_.next()),
          experimental_owned_int_(experimental_owned_int),
          experimental_rt_scalar_loop_(experimental_rt_scalar_loop) {}

    Program parse() {
        Program program;
        bool wrapped_module = false;
        if (accept("define")) {
            if (accept("serial")) {
                require("actor");
                fail("native serial actor requires shared HTTP/message lane lowering");
            }
            if (accept("module")) {
                program.module = identifier();
                accept("as");
                wrapped_module = true;
            } else {
                if (accept("class")) program.classes.push_back(class_declaration());
                else if (accept("actor")) program.actors.push_back(actor_declaration());
                else if (accept_transform_pipe_modifier()) {
                    require("actor");
                    fail("native transform-pipe actor requires typed pipeline role and transport lowering");
                } else fail("expected 'module', 'class' or 'actor'");
            }
        }
        while (token_.kind != Kind::End
                && !(wrapped_module && token_.text == "end")) {
            if (accept("define")) {
                if (accept("serial")) {
                    require("actor");
                    fail("native serial actor requires shared HTTP/message lane lowering");
                }
                if (accept("class")) program.classes.push_back(class_declaration());
                else if (accept("actor")) program.actors.push_back(actor_declaration());
                else if (accept_transform_pipe_modifier()) {
                    require("actor");
                    fail("native transform-pipe actor requires typed pipeline role and transport lowering");
                } else fail("expected 'class' or 'actor'");
            } else if (token_.text == "suspend") {
                program.cooperative_loops.push_back(cooperative_scalar_loop());
            } else {
                program.functions.push_back(function());
            }
        }
        if (wrapped_module) require("end");
        if (token_.kind != Kind::End) fail("unexpected trailing source");
        if (program.functions.empty() && program.actors.empty() &&
                program.cooperative_loops.empty())
            fail("expected at least one function or actor");
        if (!program.cooperative_loops.empty() &&
                (program.cooperative_loops.size() != 1 || !program.functions.empty() ||
                 !program.actors.empty() || !program.classes.empty() || !program.module.empty())) {
            fail("first AOT continuation slice requires exactly one standalone suspend function");
        }
        validate(program);
        return program;
    }

private:
    ActorDecl actor_declaration() {
        ActorDecl actor;
        actor.offset = token_.offset;
        actor.name = identifier();
        if (callable_nominal_name(actor.name))
            fail("callable signatures cannot be declared as nominal actor types");
        if (entry_builtin_name(actor.name))
            fail("builtin Entry is compiler-owned and cannot be declared as a nominal actor type");
        require("as");
        const bool braced = accept("{");
        if (token_.text == "state") {
            fail("native actor state { ... } requires sealed handle_state lowering; legacy scalar actor fields are not this contract");
        }
        std::unordered_set<std::string> names;
        while (token_.text == "let" || token_.text == "const" || token_.text == "val") {
            if (actor.fields.size() == 16) fail("native actor field limit exceeded (16)");
            LocalBinding field;
            field.offset = token_.offset;
            field.field_mutable = token_.text == "let";
            advance();
            field.name = identifier();
            if (!names.insert(field.name).second) fail("duplicate actor field '" + field.name + "'");
            require(":");
            require("int");
            require("=");
            field.initializer = expression();
            require(";");
            actor.fields.push_back(std::move(field));
        }
        if (token_.text == "final") {
            fail("native final actor fields require typed field initialization and ownership lowering");
        }
        if (actor.fields.empty()) fail("actor requires at least one scalar field");
        Function method;
        method.offset = token_.offset;
        method.exported = accept("pub");
        if (!method.exported) fail("native actor requires pub on_message(int)");
        // Match the Java frontend's declaration spelling contract. The native
        // actor subset still lowers only on_message(int) and cannot admit
        // arbitrary methods, static fnc members or suspension frames.
        if (token_.text == "suspend" || token_.text == "suspends") {
            fail("native suspends actor method requires continuation lowering");
        }
        // Actor method modifiers have their own admission boundary. Do not
        // allow the fixed-int actor subset to parse these as method names or
        // quietly route a read declaration through the legacy write lane.
        if (at_unsupported_critical_mode()) {
            fail("native reader/writer critical declaration requires compiler-checked RW gate and scheduler-owned logical permits");
        }
        if (accept("static")) {
            if (accept("method")) fail("static method is invalid; use static fnc");
            fail("native actor static fnc requires separate member lowering");
        }
        if (!accept("fnc")) accept("method"); // optional method marker
        if (at_unsupported_critical_mode()) {
            fail("native reader/writer critical declaration requires compiler-checked RW gate and scheduler-owned logical permits");
        }
        method.name = identifier();
        if (method.name != "on_message") fail("native actor supports only on_message");
        require("(");
        if (token_.text == "@") fail("native @Default actor method formal requires checked annotation ABI lowering");
        require("int");
        Parameter param;
        param.name = identifier();
        method.parameters.push_back(std::move(param));
        require(")");
        require(":");
        require("int");
        require("{");
        while (token_.text != "return") {
            if (token_.kind == Kind::End || token_.text == "}") fail("actor handler requires return");
            ActorFieldWrite write;
            write.offset = token_.offset;
            write.field = identifier();
            require("=");
            write.expression = expression();
            require(";");
            method.actor_field_writes.push_back(std::move(write));
        }
        require("return");
        method.result = expression();
        require(";");
        require("}");
        actor.on_message = std::move(method);
        if (braced) require("}");
        else require("end");
        return actor;
    }

    ClassDecl class_declaration() {
        ClassDecl result;
        result.name = identifier();
        if (callable_nominal_name(result.name))
            fail("callable signatures cannot be declared as nominal class types");
        if (entry_builtin_name(result.name))
            fail("builtin Entry is compiler-owned and cannot be declared as a nominal class type");
        require("as");
        std::unordered_set<std::string> seen;
        while (token_.text != "end") {
            if (token_.kind == Kind::End) fail("expected 'end'");
            const bool publicly_visible = accept("pub");
            if (accept("constructor")) {
                if (result.explicit_constructor)
                    fail("native class may declare only one scalar constructor");
                result.explicit_constructor = true;
                require("(");
                std::unordered_set<std::string> params;
                if (!accept(")")) {
                    do {
                        if (result.constructor_parameters.size() == 16)
                            fail("native scalar constructor parameter limit exceeded (16)");
                        if (token_.text == "@")
                            fail("native @Default constructor formal requires checked constant and constructor ABI lowering");
                        require("int");
                        std::string name = identifier();
                        if (!params.insert(name).second)
                            fail("duplicate native constructor parameter '" + name + "'");
                        result.constructor_parameters.push_back(std::move(name));
                    } while (accept(","));
                    require(")");
                }
                require("{");
                if (accept("return")) require(";");
                if (token_.text != "}")
                    fail("native scalar constructor body must be empty or contain only 'return;'");
                require("}");
                if (token_.text != "end")
                    fail("native scalar constructor must be the last class member");
                continue;
            }
            if (result.explicit_constructor)
                fail("native scalar constructor must be the last class member");
            if (result.fields.size() == 16)
                fail("native class field limit exceeded (16)");
            const bool legacy_let = accept("let");
            ClassField field;
            field.public_member = publicly_visible;
            bool literal_const = false;
            bool val_alias = false;
            if (!legacy_let) {
                field.final_field = accept("final");
                if (field.final_field) literal_const = accept("const");
                else if (accept("const")) literal_const = field.final_field = true;
                else field.final_field = val_alias = accept("val");
            }
            const bool explicit_mut = accept("mut");
            if (val_alias && explicit_mut) fail("duplicate mut class field qualifier");
            const bool mutable_referent = val_alias || explicit_mut;
            field.mutable_referent = mutable_referent;
            if (mutable_referent && accept("Array")) {
                // 'mut Array<int>' is canonical: mut is the referent
                // capability, independent of whether the slot is final.
                require("<"); require("int"); require(">");
                field.int_array = true;
                field.name = identifier();
                if (!seen.insert(field.name).second)
                    fail("duplicate class field '" + field.name + "'");
                if (!accept(":")) require("=");
                require("new"); require("Array");
                require("<"); require("int"); require(">");
                require("(");
                const std::size_t initializer_end = token_.offset + token_.text.size();
                require(")");
                class_field_terminator(initializer_end);
            } else {
                if (mutable_referent && token_.text != "int")
                    fail("native class field supports only scalar int or mut Array<int>");
                require("int");
                field.name = identifier();
                if (!seen.insert(field.name).second)
                    fail("duplicate class field '" + field.name + "'");
                if (!accept(":") && !accept("="))
                    fail(field.final_field
                        ? "final class field requires declaration-site initializer"
                        : "class field requires declaration-site initializer");
                const std::size_t literal_end = token_.offset + token_.text.size();
                if (literal_const && token_.kind != Kind::Integer)
                    fail("class field requires i64 literal initializer");
                auto initializer = expression();
                if (literal_const && initializer->kind != Expression::Kind::Integer)
                    fail("class field requires i64 literal initializer");
                if (initializer->kind == Expression::Kind::Integer) {
                    field.initial = initializer->integer;
                    class_field_terminator(literal_end);
                } else {
                    // General expression boundaries require explicit ';' in
                    // this limited native constructor slice.
                    require(";");
                }
                if (!native_constructor_scalar_tree(*initializer))
                    fail("native class field initializer requires pure scalar constructor parameters or integers");
                field.initializer_expression = std::move(initializer);
            }
            result.fields.push_back(std::move(field));
        }
        if (result.fields.empty()) fail("native class requires at least one field");
        std::unordered_set<std::string> allowed(
            result.constructor_parameters.begin(), result.constructor_parameters.end());
        for (const auto& field : result.fields) {
            if (field.int_array && result.explicit_constructor)
                fail("native owned Array<int> constructor requires checked failure cleanup");
            if (!field.initializer_expression) continue;
            if (!result.explicit_constructor &&
                    field.initializer_expression->kind != Expression::Kind::Integer)
                fail("native nonliteral class field initializer requires explicit scalar constructor");
            validate_expression(*field.initializer_expression, allowed, {});
        }
        require("end");
        return result;
    }

    void class_field_terminator(std::size_t initializer_end) {
        if (accept(";")) return;
        if (token_.kind == Kind::End || token_.offset < initializer_end)
            fail("class field requires ';' or a line break");
        // Unlike expression statements, native class fields may omit ';'
        // only at a real lexical line boundary. A same-line next member is
        // never silently admitted by whitespace or comments alone.
        const auto gap = source_.substr(initializer_end, token_.offset - initializer_end);
        if (gap.find('\n') == std::string_view::npos &&
                gap.find('\r') == std::string_view::npos)
            fail("class field requires ';' or a line break");
    }

    std::int64_t integer_literal() {
        if (token_.kind != Kind::Integer) fail("expected integer literal");
        std::string digits(token_.text);
        if (digits.front() == '_' || digits.back() == '_' ||
                digits.find("__") != std::string::npos) fail("malformed integer separator");
        digits.erase(std::remove(digits.begin(), digits.end(), '_'), digits.end());
        std::int64_t value = 0;
        const auto parsed = std::from_chars(digits.data(), digits.data() + digits.size(), value);
        if (parsed.ec != std::errc{} || parsed.ptr != digits.data() + digits.size())
            fail("integer literal overflows signed 64-bit result");
        advance();
        return value;
    }

    CooperativeScalarLoop cooperative_scalar_loop() {
        // Explicitly narrow syntax avoids accidentally accepting unpinned
        // loans, exception cleanup, arbitrary calls or nonresumable CFG.
        CooperativeScalarLoop loop;
        loop.offset = token_.offset;
        require("suspend");
        if (token_.text != "fnc") {
            fail("native suspends declaration requires suspension-aware borrow checking and LLVM continuation lowering");
        }
        require("fnc");
        loop.name = identifier();
        require("("); require(")");
        require(":"); require("void");
        require("{");
        require("val");
        loop.variable = identifier();
        require(":"); require("int"); require("=");
        loop.initial = integer_literal();
        require(";");
        require("while"); require("("); require("true"); require(")");
        require("{");
        if (identifier() != loop.variable)
            fail("AOT continuation requires assignment to its sole frame-owned scalar");
        require("=");
        if (identifier() != loop.variable)
            fail("AOT continuation update must read its frame-owned scalar");
        require("+");
        if (integer_literal() != 1)
            fail("first AOT continuation supports only increment by one");
        require(";");
        require("}");
        require("}");
        return loop;
    }

    Function function() {
        Function value;
        value.offset = token_.offset;
        value.exported = accept("pub");
        if (token_.text == "pure") {
            fail("native pure modifier awaits transitive observable-effect verification");
        }
        if (exceptional_control(token_.text))
            fail("native exception/control effect unsupported");
        if (token_.text == "gpu" || token_.text == "cpu" || token_.text == "quantum") {
            fail("native execution domain lowering unsupported");
        }
        if (token_.text == "suspends" || token_.text == "suspend") {
            fail("native suspends declaration requires suspension-aware borrow checking and LLVM continuation lowering");
        }
        // Check compound READ/WRITE modes before handling legacy critical,
        // otherwise declaration parsing could report WRITE-only semantics.
        if (at_unsupported_critical_mode()) {
            fail("native reader/writer critical declaration requires compiler-checked RW gate and scheduler-owned logical permits");
        }
        if (token_.text == "critical") {
            fail("native critical declaration requires compiler lowering and scheduler-owned logical critical tickets");
        }
        if (token_.text == "actor" || token_.text == "async" ||
            token_.text == "generator" || token_.text == "isoactor") {
            fail("native actor/async declaration requires actor frames, suspension-aware borrow checking and LLVM lowering");
        }
        if (!accept("fnc") && !accept("routine")) fail("expected fnc or routine");
        if (at_unsupported_critical_mode()) {
            fail("native reader/writer critical declaration requires compiler-checked RW gate and scheduler-owned logical permits");
        }
        value.name = identifier();
        if (accept("<")) {
            if (accept(">")) fail("native generic declaration cannot be empty");
            do {
                GenericParameter slot;
                slot.name = identifier();
                if (slot.name == "_" || slot.name.rfind("$", 0) == 0)
                    fail("invalid generic parameter name");
                for (const auto& prior : value.generic_parameters)
                    if (prior.name == slot.name)
                        fail("duplicate generic parameter '" + slot.name + "'");
                if (accept("=")) {
                    if (accept("int")) slot.default_type = "int";
                    else {
                        const std::string dependency = identifier();
                        const bool earlier = std::any_of(value.generic_parameters.begin(),
                            value.generic_parameters.end(),
                            [&](const GenericParameter& p) { return p.name == dependency; });
                        if (!earlier) fail("forward or self-referential generic default");
                        slot.default_type = dependency;
                    }
                }
                value.generic_parameters.push_back(std::move(slot));
            } while (accept(","));
            require(">");
        }
        if (value.name == "main" && !value.generic_parameters.empty())
            fail("native entrypoint main cannot be generic");
        require("(");
        if (!accept(")")) {
            do {
                if (token_.text == "@")
                    fail("native @Default parameter annotation requires checked literal, Option and ownership ABI lowering");
                Parameter parameter;
                if (accept("borrow")) {
                    parameter.mode = accept("mut") ? Parameter::Mode::BorrowMut
                                                    : Parameter::Mode::Borrow;
                }
                if (accept("int")) {
                    parameter.scalar_type = ScalarType::I64;
                } else if (accept("bool")) {
                    if (parameter.mode != Parameter::Mode::Value)
                        fail("native bool borrow ABI is not yet admitted; pass bool by value");
                    parameter.scalar_type = ScalarType::Bool;
                } else {
                    const std::size_t type_offset = token_.offset;
                    const std::string type = identifier();
                    if (entry_builtin_name(type)) reject_native_entry_boundary(type_offset);
                    const bool generic = std::any_of(value.generic_parameters.begin(),
                        value.generic_parameters.end(),
                        [&](const GenericParameter& p) { return p.name == type; });
                    if (generic) parameter.generic_type = type;
                    else parameter.class_name = type;
                }
                if (token_.text == "?")
                    fail("native optional type '?' requires versioned Option/default ABI lowering");
                parameter.name = identifier();
                if (token_.text == "?")
                    fail("native optional parameter presence '?' requires versioned default/presence ABI lowering");
                value.parameters.push_back(std::move(parameter));
            } while (accept(","));
            require(")");
        }
        require(":");
        value.returns_void = accept("void");
        if (!value.returns_void) {
            if (accept("int")) {
                value.return_type = ScalarType::I64;
            } else if (accept("bool")) {
                value.return_type = ScalarType::Bool;
            } else {
                const std::size_t type_offset = token_.offset;
                const std::string type = identifier();
                if (entry_builtin_name(type)) reject_native_entry_boundary(type_offset);
                const bool generic = std::any_of(value.generic_parameters.begin(),
                    value.generic_parameters.end(),
                    [&](const GenericParameter& p) { return p.name == type; });
                if (!generic) fail("native return type requires int, bool or scalar generic slot");
                value.return_type = ScalarType::I64;
            }
        }
        if (token_.text == "?")
            fail("native optional return type '?' requires versioned Option/default ABI lowering");
        if (accept("using")) {
            value.has_declared_effects = true;
            do {
                std::string name = identifier();
                if (accept(".")) name += "." + identifier();
                FunctionEffect effect;
                try { effect = FunctionEffects::parse(name); }
                catch (const std::invalid_argument&) {
                    fail("unknown function effect '" + name + "'");
                }
                const auto bit = static_cast<std::uint32_t>(effect);
                if (value.declared_effects.bits & bit)
                    fail("duplicate function effect '" + name + "'");
                value.declared_effects.bits |= bit;
            } while (accept(","));
        }
        if (accept("requires")) {
            // Never accept an uncertified realtime property as a mere annotation.
            fail("native requires clause awaits verified WCET/allocation/blocking analysis");
        }
        require("{");
        while (token_.text == "const" || token_.text == "val" || token_.text == "let") {
            LocalBinding binding;
            binding.offset = token_.offset;
            if (accept("let")) {
                // Preserve the Java/Graal distinction between immutable
                // let Class and exclusively borrowable let mut Class.
                binding.owner_mutable = accept("mut");
                // Class construction is stack-only. No reference to an
                // uninitialized, moved, or temporary class can escape.
                if (token_.text == "Array") {
                    // Native slice #265: only a lexical, exclusively owned Array<int>.
                    // No implicit copies, fields, captures, cross-call transfers, or
                    // suspension can enter the current synchronous array ABI.
                    if (!binding.owner_mutable)
                        fail("native Array<int> requires let mut owner");
                    require("Array");
                    require("<");
                    require("int");
                    require(">");
                    binding.name = identifier();
                    require("=");
                    require("new");
                    require("Array");
                    require("<");
                    require("int");
                    require(">");
                    require("(");
                    require(")");
                    binding.int_array = true;
                } else {
                binding.class_name = identifier();
                binding.name = identifier();
                require("=");
                if (accept("new")) {
                    const std::string ctor = identifier();
                    if (ctor != binding.class_name) fail("class constructor type mismatch");
                    require("(");
                    if (!accept(")")) {
                        do binding.constructor_arguments.push_back(expression());
                        while (accept(","));
                        require(")");
                    }
                    binding.new_class = true;
                } else {
                    require("rt");
                    require("take");
                    const bool parenthesized = accept("(");
                    binding.move_source = identifier();
                    if (parenthesized) require(")");
                    binding.move_class = true;
                }
                }
            } else {
                // A val owns mutable scalar storage; const permits only shared
                // loans. Preserve the distinction for native ownership checks.
                binding.owner_mutable = token_.text == "val";
                advance();
                binding.name = identifier();
                // Distinguish actor capabilities from scalar data before IR.
                // Unsupported generic futures never become fake blocking calls.
                if (token_.text != ":" && token_.text != "=") require(":");
                const bool explicit_type = accept(":");
                SourceType declared_type;
                bool typed_actor_ref = false;
                if (explicit_type) {
                    if (accept("ActorRef")) {
                        require("<");
                        declared_type.kind = SourceType::Kind::ActorRef;
                        declared_type.argument = identifier();
                        require(">");
                        typed_actor_ref = true;
                    } else if (accept("Future")) {
                        require("<");
                        declared_type.kind = SourceType::Kind::Future;
                        if (accept("int")) declared_type.argument = "int";
                        else declared_type.argument = identifier();
                        require(">");
                        fail("Future<T> requires native continuation lowering");
                    } else {
                        if (token_.text == "Entry")
                            reject_native_entry_boundary(token_.offset);
                        if (token_.text == "owned" && !experimental_owned_int_)
                            fail("experimental owned int requires --experimental-owned-int");
                        binding.heap_owned = experimental_owned_int_ && accept("owned");
                        if (accept("int")) {
                            binding.scalar_type = ScalarType::I64;
                        } else if (accept("bool")) {
                            if (binding.heap_owned)
                                fail("native owned bool region lowering is not admitted");
                            binding.scalar_type = ScalarType::Bool;
                        } else {
                            fail("native scalar local requires int or bool");
                        }
                    }
                }
                require("=");
                if (accept("spawn")) {
                    if (explicit_type && !typed_actor_ref)
                        fail("spawn initializer requires ActorRef<T> type or inference");
                    ActorSpawnBinding spawned;
                    spawned.name = binding.name;
                    spawned.offset = binding.offset;
                    spawned.actor_type = identifier();
                    spawned.declared_type.kind = SourceType::Kind::ActorRef;
                    spawned.declared_type.argument =
                        typed_actor_ref ? declared_type.argument : spawned.actor_type;
                    if (spawned.declared_type.argument != spawned.actor_type)
                        fail("ActorRef<T> type mismatch with spawned actor");
                    require("(");
                    require(")");
                    require(";");
                    value.actor_spawns.push_back(std::move(spawned));
                    continue;
                }
                if (typed_actor_ref) fail("ActorRef<T> requires spawn initializer");
                if (!explicit_type) fail("scalar local requires an explicit ': int' or ': bool' type");
                binding.initializer = expression();
            }
            require(";");
            value.locals.push_back(std::move(binding));
        }
        // Restrict the first general-source CFG slice to synchronous
        // if/else arms containing field writes or direct function calls.
        // They may change owned scalar state only when both predecessors
        // agree at the join; checked below by the ownership verifier.
        auto branch_arm = [&](bool finally_scope = false) {
            std::vector<Statement> actions;
            while (token_.text != "}") {
                if (token_.kind == Kind::End || actions.size() == 16)
                    fail("native conditional arm limit exceeded (16)");
                if (token_.text == "while" || token_.text == "for" ||
                        token_.text == "loop") {
                    fail("native realtime source loop requires verified LLVM continuation lowering");
                }
                if (token_.text == "rt") {
                    advance();
                    if (token_.text == "cooperate") {
                        throw std::runtime_error(
                            "native rt cooperate requires verified heap-owned continuation lowering"
                            " at byte " + std::to_string(token_.offset));
                    }
                    fail("unsupported native runtime operation in conditional arm");
                }
                Statement statement;
                statement.offset = token_.offset;
                if (finally_scope && (token_.text == "return" ||
                        token_.text == "break" || token_.text == "continue"))
                    fail("return/break/continue cannot escape finally; finally is cleanup-only");
                if (finally_scope && (token_.text == "defer" || token_.text == "try"))
                    fail("native finally nested defer/try requires scoped cleanup lowering");
                if (token_.text == "let")
                    fail("native branch-local class owner requires destructor/drop lowering");
                if (accept("const") || accept("val")) {
                    statement.kind = Statement::Kind::ScopedLocal;
                    statement.branch_local = std::make_unique<LocalBinding>();
                    statement.branch_local->offset = statement.offset;
                    statement.branch_local->name = identifier();
                    require(":");
                    if (accept("int")) statement.branch_local->scalar_type = ScalarType::I64;
                    else if (accept("bool")) statement.branch_local->scalar_type = ScalarType::Bool;
                    else fail("native branch-local scalar requires int or bool");
                    require("=");
                    statement.branch_local->initializer = expression();
                    require(";");
                    actions.push_back(std::move(statement));
                    continue;
                }
                auto target = primary();
                if (target->kind == Expression::Kind::Field && accept("=")) {
                    if (!target->left || target->left->kind != Expression::Kind::Variable)
                        fail("native conditional requires named class field");
                    statement.kind = Statement::Kind::FieldWrite;
                    statement.receiver = target->left->name;
                    statement.field = target->name;
                    statement.expression = expression();
                } else if (target->kind == Expression::Kind::Call ||
                           target->kind == Expression::Kind::ArrayCall) {
                    statement.kind = Statement::Kind::Call;
                    statement.expression = std::move(target);
                } else {
                    fail("native conditional arm requires field assignment or direct call");
                }
                require(";");
                actions.push_back(std::move(statement));
            }
            return actions;
        };
        bool return_guards_started = false;
        bool terminal_try_finally = false;
        std::size_t branch_count = 0;
        while (token_.text != "return") {
            if (token_.text == "while" && experimental_rt_scalar_loop_) {
                if (value.scalar_while || value.locals.size() != 1 ||
                        !value.statements.empty() || !value.early_returns.empty() ||
                        !value.actor_spawns.empty() || !value.actor_actions.empty() ||
                        value.has_declared_effects || !value.parameters.empty() ||
                        value.returns_void)
                    fail("experimental scalar loop requires one local and no effects");
                const LocalBinding& local = value.locals.front();
                if (!local.owner_mutable || local.heap_owned || local.int_array ||
                        !local.class_name.empty() || !local.initializer ||
                        local.initializer->kind != Expression::Kind::Integer)
                    fail("experimental scalar loop requires val scalar integer initializer");
                auto loop = std::make_unique<ScalarWhileLoop>();
                loop->counter = local.name;
                loop->initial = local.initializer->integer;
                require("while");
                require("(");
                if (identifier() != loop->counter)
                    fail("experimental scalar loop condition must read its counter");
                require("<");
                loop->upper_bound = integer_literal();
                require(")");
                require("{");
                if (identifier() != loop->counter)
                    fail("experimental scalar loop must update its counter");
                require("=");
                if (identifier() != loop->counter)
                    fail("experimental scalar loop cannot read another owner");
                require("+");
                if (integer_literal() != 1)
                    fail("experimental scalar loop increment must be exactly one");
                require(";");
                require("}");
                if (loop->initial < 0 || loop->upper_bound < loop->initial ||
                        loop->upper_bound > 1000000000LL)
                    fail("experimental scalar loop requires bounded nonnegative i64 range");
                value.scalar_while = std::move(loop);
                if (token_.text != "return")
                    fail("experimental scalar loop must be followed by a single return");
                continue;
            }
            if (token_.text == "while" || token_.text == "for" ||
                    token_.text == "loop") {
                fail("native realtime source loop requires verified LLVM continuation lowering");
            }
            if (token_.text == "try") {
                if (terminal_try_finally || return_guards_started)
                    fail("native terminal try/finally must be a single return-only scope");
                terminal_try_finally = true;
                require("try");
                require("{");
                if (token_.text == "defer" || token_.text == "throw") {
                    // First typed-i64 exceptional CFG: one unconditional throw,
                    // LIFO scoped field cleanups, and a local catch. No await,
                    // exception-producing calls or nested try are admitted.
                    if (value.returns_void || !value.statements.empty() ||
                            !value.actor_actions.empty())
                        fail("native caught throw requires a single terminal int try scope");
                    // No partially inferred effect set can masquerade as a
                    // complete exception/cleanup summary at this boundary.
                    if (value.has_declared_effects)
                        throw std::runtime_error(
                            "native caught throw effect-qualified function unsupported at byte " +
                            std::to_string(token_.offset));
                    while (accept("defer")) {
                        if (value.caught_throw_defers.size() == 8)
                            fail("native caught throw defer limit exceeded (8)");
                        Statement deferred;
                        deferred.kind = Statement::Kind::FieldWrite;
                        deferred.offset = token_.offset;
                        auto target = primary();
                        if (target->kind != Expression::Kind::Field ||
                                !target->left ||
                                target->left->kind != Expression::Kind::Variable)
                            fail("native caught throw defer requires named class field write");
                        deferred.receiver = target->left->name;
                        deferred.field = target->name;
                        require("=");
                        deferred.expression = expression();
                        if (!native_pure_scalar_tree(*deferred.expression))
                            fail("native caught throw defer requires nonthrowing scalar/field expression");
                        require(";");
                        value.caught_throw_defers.push_back(std::move(deferred));
                    }
                    require("throw");
                    value.caught_throw_value = expression();
                    if (!native_pure_scalar_tree(*value.caught_throw_value))
                        fail("native caught throw requires nonthrowing int payload");
                    require(";");
                    require("}");
                    require("catch");
                    require("(");
                    value.catch_binding = identifier();
                    for (const auto& param : value.parameters)
                        if (param.name == value.catch_binding)
                            fail("native caught throw binding shadows parameter");
                    for (const auto& local : value.locals)
                        if (local.name == value.catch_binding)
                            fail("native caught throw binding shadows local owner");
                    require(")");
                    require("{");
                    require("return");
                    value.result = expression();
                    if (!native_pure_scalar_tree(*value.result))
                        fail("native caught throw catch return requires nonthrowing scalar/field read");
                    require(";");
                    require("}");
                    require("finally");
                    require("{");
                    auto cleanup = branch_arm(true);
                    require("}");
                    for (auto it = cleanup.rbegin(); it != cleanup.rend(); ++it) {
                        if (it->kind != Statement::Kind::FieldWrite ||
                                !native_pure_scalar_tree(*it->expression))
                            fail("native caught throw finally requires nonthrowing field write");
                        it->kind = Statement::Kind::Defer;
                        value.statements.push_back(std::move(*it));
                    }
                    require("}");
                    return value;
                }
                continue;
            }
            // A void program entrypoint may fall through with success status 0.
            // Other functions retain their existing explicit-return requirement.
            if (token_.text == "}" && value.returns_void &&
                    value.name == "main" && value.parameters.empty()) {
                require("}");
                return value;
            }
            if (token_.kind == Kind::End || token_.text == "}") {
                if (return_guards_started) fail("expected 'return'");
                fail("missing final return");
            }
            // The initial native finally slice admits only guarded returns
            // and a terminal return in the protected region. No potentially
            // throwing calls, nested effects or suspension can be skipped.
            if (terminal_try_finally && token_.text != "if")
                fail("native terminal try supports only return guards and final return");
            if (token_.text == "if") {
                const auto offset = token_.offset;
                require("if");
                require("(");
                auto condition = expression();
                if (terminal_try_finally && !native_pure_scalar_tree(*condition))
                    fail("native protected try guard requires a pure nonthrowing bool expression");
                require(")");
                require("{");
                if (accept("return")) {
                    if (value.returns_void)
                        fail("guarded early return requires a scalar result");
                    if (value.early_returns.size() == 8)
                        fail("native guarded return limit exceeded (8)");
                    GuardedReturn guard;
                    guard.offset = offset;
                    guard.condition = std::move(condition);
                    guard.result = expression();
                    if (terminal_try_finally && !nonthrowing_protected_read(*guard.result))
                        fail("native protected try return requires nonthrowing scalar/field read");
                    require(";");
                    require("}");
                    require("fi");
                    value.early_returns.push_back(std::move(guard));
                    return_guards_started = true;
                } else {
                    if (terminal_try_finally)
                        fail("native terminal try supports only guarded return branches");
                    if (return_guards_started)
                        fail("native conditional statements cannot follow return guards");
                    if (++branch_count > 8)
                        fail("native conditional CFG limit exceeded (8)");
                    Statement conditional;
                    conditional.kind = Statement::Kind::Conditional;
                    conditional.offset = offset;
                    conditional.condition = std::move(condition);
                    conditional.then_statements = branch_arm();
                    require("}");
                    require("else");
                    require("{");
                    conditional.else_statements = branch_arm();
                    require("}");
                    require("fi");
                    value.statements.push_back(std::move(conditional));
                }
                continue;
            }
            if (return_guards_started)
                fail("native statements cannot follow guarded returns");
            if (token_.text == "await") {
                const std::size_t await_offset = token_.offset;
                advance();
                if (!value.ready_await_actor.empty())
                    fail("native source supports only one await get_ready continuation");
                if (!value.actor_actions.empty() || !value.statements.empty())
                    fail("await get_ready must precede post-await actor effects");
                value.ready_await_actor = identifier();
                value.ready_await_offset = await_offset;
                require(".");
                require("get_ready");
                require("(");
                require(")");
                require(";");
                continue;
            }
            if (token_.text != "defer" && exceptional_control(token_.text))
                fail("native exception/control effect unsupported");
            if (accept("defer")) {
                Statement deferred;
                deferred.kind = Statement::Kind::Defer;
                deferred.offset = token_.offset;
                auto target = primary();
                if (target->kind == Expression::Kind::Field && accept("=")) {
                    if (!target->left || target->left->kind != Expression::Kind::Variable)
                        fail("native deferred field assignment requires named class receiver");
                    deferred.receiver = target->left->name;
                    deferred.field = target->name;
                    deferred.expression = expression();
                } else if (target->kind == Expression::Kind::Call) {
                    deferred.expression = std::move(target);
                } else {
                    fail("native defer requires a direct function call or class field assignment");
                }
                require(";");
                value.statements.push_back(std::move(deferred));
                continue;
            }
            if (accept("stop")) {
                value.actor_stops.push_back(identifier());
                require(";");
                value.actor_actions.push_back({ActorAction::Kind::Stop,
                                               value.actor_stops.size() - 1});
                continue;
            }
            if (token_.text == "rt") {
                const auto intrinsic_offset = token_.offset;
                advance();
                if (accept("array_commit_filled")) {
                    // Restricted, compiler-only transaction over four
                    // owned array fields in declaration order.
                    Statement effect;
                    effect.kind = Statement::Kind::ArrayCommitFilled;
                    effect.offset = intrinsic_offset;
                    require("(");
                    effect.receiver = identifier();
                    require(",");
                    effect.array_commit_length = expression();
                    for (std::size_t i = 0; i < 4; ++i) {
                        require(",");
                        effect.array_commit_values.push_back(expression());
                    }
                    require(")");
                    require(";");
                    value.statements.push_back(std::move(effect));
                    continue;
                }
                if (token_.text == "cooperate") {
                    throw std::runtime_error(
                            "native rt cooperate requires verified heap-owned continuation lowering"
                            " at byte " + std::to_string(token_.offset));
                }
                fail("native rt statement unsupported until suspension lowering");
            }
            Statement statement;
            statement.offset = token_.offset;
            auto action = primary();
            if (action->kind == Expression::Kind::Field && action->name == "send"
                    && accept("(")) {
                // Nested class receivers cannot alias unrelated ActorRef names.
                if (!action->left || action->left->kind != Expression::Kind::Variable)
                    fail("native actor send requires direct named ActorRef receiver");
                ActorSend send;
                send.offset = statement.offset;
                send.reference = action->left->name;
                send.payload = expression();
                require(")");
                require(";");
                value.actor_sends.push_back(std::move(send));
                value.actor_actions.push_back({ActorAction::Kind::Send,
                                               value.actor_sends.size() - 1});
                continue;
            }
            if (action->kind == Expression::Kind::Field && accept("=")) {
                // No nested assignment may resolve through a flattened field.
                if (!action->left || action->left->kind != Expression::Kind::Variable)
                    fail("native field assignment requires direct named class receiver");
                statement.kind = Statement::Kind::FieldWrite;
                statement.receiver = action->left->name;
                statement.field = action->name;
                statement.expression = expression();
            } else if (action->kind == Expression::Kind::Call ||
                       action->kind == Expression::Kind::ArrayCall) {
                statement.kind = Statement::Kind::Call;
                statement.expression = std::move(action);
            } else {
                fail("expected class field assignment or function call statement");
            }
            require(";");
            value.statements.push_back(std::move(statement));
        }
        require("return");
        if (!value.returns_void) {
            value.result = expression();
            if (value.scalar_while &&
                    (value.result->kind != Expression::Kind::Variable ||
                     value.result->name != value.scalar_while->counter))
                fail("experimental scalar loop must return the counter");
            if (terminal_try_finally && !nonthrowing_protected_read(*value.result))
                fail("native protected try return requires nonthrowing scalar/field read");
        }
        require(";");
        if (terminal_try_finally) {
            require("}");
            // Java/Graal syntax requires catch. This native subset has no
            // throw-capable operations inside try; catch must be empty and
            // remains unreachable until typed exceptional CFG is implemented.
            require("catch");
            require("(");
            identifier();
            require(")");
            require("{");
            // Native try cannot throw: allow the Java-required catch return
            // only as a literal sentinel. It is unreachable by construction,
            // never lowered as a fake catch handler.
            if (accept("return")) {
                if (value.returns_void)
                    fail("native nonthrowing catch return requires int result");
                if (token_.kind != Kind::Integer)
                    fail("native nonthrowing catch requires only an integer literal return");
                integer_literal();
                require(";");
            }
            if (token_.text != "}")
                fail("native nonthrowing catch requires only an optional integer literal return");
            require("}");
            require("finally");
            require("{");
            auto cleanup = branch_arm(true);
            require("}");
            // The protected region always exits via an evaluated scalar
            // return. Lower its finally effects as scope-exit actions
            // registered in reverse order: finally runs in source order,
            // before any previously registered outer-scope defers.
            for (auto it = cleanup.rbegin(); it != cleanup.rend(); ++it) {
                if (it->kind != Statement::Kind::Call &&
                        it->kind != Statement::Kind::FieldWrite)
                    fail("native finally permits only direct calls and class field writes");
                it->kind = Statement::Kind::Defer;
                value.statements.push_back(std::move(*it));
            }
        }
        require("}");
        return value;
    }

    std::unique_ptr<Expression> expression() { return conditional(); }

    std::unique_ptr<Expression> conditional() {
        if (++conditional_depth_ > kMaxNativeExpressionDepth) {
            --conditional_depth_;
            fail("native conditional expression nesting limit exceeded (64)");
        }
        struct ConditionalDepthGuard {
            std::size_t& depth;
            ~ConditionalDepthGuard() { --depth; }
        } depth_guard{conditional_depth_};

        auto condition = logical_or();
        if (!accept("?")) return condition;

        auto result = make_expression(Expression::Kind::Conditional, condition->offset);
        result->condition = std::move(condition);
        result->when_true = conditional();
        require(":");
        result->when_false = conditional();
        return result;
    }

    std::unique_ptr<Expression> logical_or() {
        auto left = logical_xor();
        while (token_.text == "||") {
            const auto offset = token_.offset;
            advance();
            auto expr = make_expression(Expression::Kind::LogicalOr, offset);
            expr->left = std::move(left);
            expr->right = logical_xor();
            left = std::move(expr);
        }
        return left;
    }

    std::unique_ptr<Expression> logical_xor() {
        auto left = logical_and();
        while (token_.text == "^^") {
            const auto offset = token_.offset;
            advance();
            auto expr = make_expression(Expression::Kind::LogicalXor, offset);
            expr->left = std::move(left);
            expr->right = logical_and();
            left = std::move(expr);
        }
        return left;
    }

    std::unique_ptr<Expression> logical_and() {
        auto left = equality();
        while (token_.text == "&&") {
            const auto offset = token_.offset;
            advance();
            auto expr = make_expression(Expression::Kind::LogicalAnd, offset);
            expr->left = std::move(left);
            expr->right = equality();
            left = std::move(expr);
        }
        return left;
    }

    std::unique_ptr<Expression> equality() {
        auto left = comparison();
        while (token_.text == "==" || token_.text == "eq" ||
                token_.text == "!=" || token_.text == "neq") {
            const auto offset = token_.offset;
            const bool equal = token_.text == "==" || token_.text == "eq";
            advance();
            auto expr = make_expression(
                equal ? Expression::Kind::Equal : Expression::Kind::NotEqual, offset);
            expr->left = std::move(left);
            expr->right = comparison();
            left = std::move(expr);
        }
        return left;
    }

    std::unique_ptr<Expression> comparison() {
        auto left = additive();
        while (token_.text == "<" || token_.text == "<=" ||
                token_.text == ">" || token_.text == ">=") {
            const auto offset = token_.offset;
            const auto op = token_.text;
            advance();
            auto kind = op == "<" ? Expression::Kind::Less
                : op == "<=" ? Expression::Kind::LessEqual
                : op == ">" ? Expression::Kind::Greater
                : Expression::Kind::GreaterEqual;
            auto expr = make_expression(kind, offset);
            expr->left = std::move(left);
            expr->right = additive();
            left = std::move(expr);
        }
        return left;
    }

    std::unique_ptr<Expression> additive() {
        auto left = product();
        while (token_.text == "+" || token_.text == "-") {
            const auto offset = token_.offset;
            const auto op = token_.text;
            advance();
            auto expr = make_expression(op == "+" ? Expression::Kind::Add
                                                 : Expression::Kind::Subtract, offset);
            expr->left = std::move(left);
            expr->right = product();
            left = std::move(expr);
        }
        return left;
    }

    std::unique_ptr<Expression> product() {
        auto left = unary();
        while (token_.text == "*" || token_.text == "/" || token_.text == "%") {
            // Java defines int/int division as a FLOAT result; this native
            // source compiler lowers expressions only to signed i64. Reject
            // at the operator before either lowering or emitting partial IR.
            if (token_.text == "/")
                fail("native floating division unsupported: int/int yields float in Java; "
                     "LLVM float typing and zero-divisor semantics are not implemented");
            if (token_.text == "%")
                fail("native integer remainder unsupported: zero-divisor and overflow contracts "
                     "are not implemented");
            const auto offset = token_.offset;
            advance();
            auto expr = make_expression(Expression::Kind::Multiply, offset);
            expr->left = std::move(left);
            expr->right = unary();
            left = std::move(expr);
        }
        return left;
    }

    std::unique_ptr<Expression> unary() {
        if (++expression_depth_ > kMaxNativeExpressionDepth) {
            --expression_depth_;
            fail("native expression nesting limit exceeded (64)");
        }
        struct DepthGuard {
            std::size_t& depth;
            ~DepthGuard() { --depth; }
        } depth_guard{expression_depth_};

        if (token_.text == "trap") {
            // Reserved for Option-valued ordinary-error capture. Reject it
            // before IR generation: accepting a normal value here would erase
            // the guest failure channel and violate panic/raise precedence.
            fail("native trap expression unsupported: typed throw effects and cleanup lowering required");
        }
        if (token_.text == "rt") {
            const auto offset = token_.offset;
            advance();
            const auto operation = token_.text;
            if (operation == "cooperate") {
                throw std::runtime_error(
                            "native rt cooperate requires verified heap-owned continuation lowering"
                            " at byte " + std::to_string(token_.offset));
            }
            if (operation != "borrow" && operation != "copy" &&
                operation != "take" && operation != "share") {
                fail("unsupported native rt operation; suspension/ownership lowering is unavailable");
            }
            advance();
            const bool mutable_borrow = operation == "borrow" && accept("mut");
            auto result = make_expression(
                operation == "copy" ? Expression::Kind::Copy :
                operation == "take" ? Expression::Kind::Take :
                operation == "share" ? Expression::Kind::Share :
                mutable_borrow ? Expression::Kind::BorrowMut : Expression::Kind::Borrow,
                offset);
            if (accept("(")) {
                result->left = expression();
                require(")");
            } else {
                result->left = unary();
            }
            return result;
        }
        if (token_.text == "-") {
            const auto offset = token_.offset;
            advance();
            auto expr = make_expression(Expression::Kind::Negate, offset);
            expr->left = unary();
            return expr;
        }
        if (token_.text == "!") {
            const auto offset = token_.offset;
            advance();
            auto expr = make_expression(Expression::Kind::LogicalNot, offset);
            expr->left = unary();
            return expr;
        }
        return primary();
    }

    std::unique_ptr<Expression> primary() {
        const auto offset = token_.offset;
        if (accept("(")) {
            // Java/Graal's () is a zero-slot Tuple, not grouping.
            // Native scalar lowering must reject all tuple arities before IR.
            if (token_.text == ")") {
                fail("native tuple literal unsupported: fixed product layout and ownership lowering are not implemented");
            }
            auto inner = expression();
            if (token_.text == ",") {
                fail("native tuple literal unsupported: fixed product layout and ownership lowering are not implemented");
            }
            require(")");
            return inner;
        }
        if (token_.kind == Kind::Integer) {
            auto expr = make_expression(Expression::Kind::Integer, offset);
            expr->integer = integer_literal();
            return expr;
        }
        if (token_.text == "true" || token_.text == "false") {
            auto expr = make_expression(Expression::Kind::Boolean, offset);
            expr->boolean = token_.text == "true";
            advance();
            return expr;
        }
        // Explicit brace tuples and nominal Entry values have no LLVM native
        // product ABI. Do not lower them as scalars, and do not steal ordinary
        // calls to a user function named tuple(...).
        if (token_.text == "tuple") {
            Lexer lookahead = lexer_;
            if (lookahead.next().text == "{") {
                fail("native tuple literal unsupported: fixed product layout and ownership lowering are not implemented");
            }
        }
        if (token_.text == "Entry") {
            Lexer lookahead = lexer_;
            const Token next = lookahead.next();
            // '<' in native expressions is currently parsed as generic-call
            // syntax, so Entry<...> must fail before scalar lowering.
            if (next.text == "." || next.text == "<") {
                fail("native Entry<K,V> unsupported: nominal key/value product layout and ownership lowering are not implemented");
            }
        }
        // Opaque ActorGroup and join permits are runtime-affine capabilities.
        // Never silently admit a native scalar name or emit partial IR.
        if (token_.text == "ActorGroup" ||
            token_.text == "ActorGroupJoinCapability")
            fail("native ActorGroup source lowering unsupported: "
                 "typed membership capabilities and lifecycle authority required");
        if (token_.kind != Kind::Identifier || reserved(token_.text)) {
            fail("expected int expression");
        }
        auto expr = make_expression(Expression::Kind::Variable, offset);
        expr->name = identifier();
        if (generic_call_type_arguments_ahead()) {
            require("<");
            expr->type_arguments_present = true;
            if (!accept(">")) {
                bool seen_named = false;
                do {
                    GenericTypeArgument argument;
                    if (token_.kind == Kind::Identifier && token_.text != "int") {
                        Lexer lookahead = lexer_;
                        if (lookahead.next().text == "=") {
                            argument.name = identifier();
                            require("=");
                            seen_named = true;
                        }
                    }
                    if (argument.name.empty() && seen_named)
                        fail("positional generic argument after named argument");
                    if (accept("_")) argument.infer = true;
                    else if (accept("int")) argument.type = "int";
                    else fail("native scalar generic type requires int or bare '_' (non-scalar generics unsupported)");
                    expr->type_arguments.push_back(std::move(argument));
                } while (accept(","));
                require(">");
            }
            if (token_.text != "(")
                fail("generic type arguments require a call");
        }
        if (accept("(")) {
            expr->kind = Expression::Kind::Call;
            bool named_seen = false;
            std::unordered_set<std::string> labels;
            if (!accept(")")) {
                do {
                    if (named_argument_ahead()) {
                        std::string label = identifier();
                        require(":");
                        if (!labels.insert(label).second) {
                            fail("duplicate named argument '" + label + "'");
                        }
                        named_seen = true;
                        expr->argument_names.push_back(std::move(label));
                    } else {
                        if (named_seen) fail("positional argument cannot follow named argument");
                        expr->argument_names.emplace_back();
                    }
                    expr->arguments.push_back(expression());
                } while (accept(","));
                require(")");
            }
        }
        // Class-owned array expressions are intentionally limited to one
        // field selection followed by a native array method or .size.
        while (accept(".")) {
            auto field = make_expression(Expression::Kind::Field, offset);
            field->left = std::move(expr);
            field->name = identifier();
            // Keep ActorRef.send(...) for the outer actor action parser.
            // Eager ArrayCall parsing would erase the send/stop ordering.
            if (field->name != "send" && accept("(")) {
                field->kind = Expression::Kind::ArrayCall;
                if (!accept(")")) {
                    do {
                        if (named_argument_ahead()) {
                            fail("native array method arguments have no named parameter contract");
                        }
                        field->arguments.push_back(expression());
                    } while (accept(","));
                    require(")");
                }
            }
            expr = std::move(field);
            if (expr->kind == Expression::Kind::ArrayCall) break;
        }
        return expr;
    }

    bool generic_call_type_arguments_ahead() const {
        if (token_.text != "<") return false;
        Lexer probe = lexer_; // already positioned immediately after '<'
        std::size_t budget = 0;
        while (++budget <= 64) {
            const Token next = probe.next();
            if (next.kind == Kind::End) return false;
            if (next.text == ">") return probe.next().text == "(";
            if (next.text == "," || next.text == "=" || next.text == "_" ||
                    next.text == "int" || next.kind == Kind::Identifier)
                continue;
            return false;
        }
        return false;
    }

    bool named_argument_ahead() const {
        if (token_.kind != Kind::Identifier || reserved(token_.text)) return false;
        Lexer lookahead = lexer_;
        return lookahead.next().text == ":";
    }

    bool accept(std::string_view text) {
        if (token_.text != text) return false;
        advance();
        return true;
    }

    // Consult a copy of the scanner rather than merging arithmetic tokens.
    // The original scanner position and error offset remain unchanged.
    bool at_unsupported_critical_mode() const {
        if (token_.text != "critical") return false;
        Lexer probe = lexer_;
        const Token dash = probe.next();
        if (dash.text != "-" || dash.offset != token_.offset + token_.text.size())
            return false;
        const Token mode = probe.next();
        return (mode.text == "read" || mode.text == "write")
            && mode.offset == dash.offset + 1;
    }

    // Parse the role only after 'define'. A plain transform-pipe expression
    // must still lex as identifier, minus, identifier.
    bool accept_transform_pipe_modifier() {
        if (token_.text != "transform") return false;
        const auto start = token_.offset;
        advance();
        if (token_.text != "-" || token_.offset != start + 9)
            fail("expected contiguous transform-pipe actor modifier");
        advance();
        if (token_.text != "pipe" || token_.offset != start + 10)
            fail("expected contiguous transform-pipe actor modifier");
        advance();
        return true;
    }

    void require(std::string_view text) {
        if (!accept(text)) fail("expected '" + std::string(text) + "'");
    }

    std::string identifier() {
        // SelectPlan and SelectCase are source-level linear ownership
        // capabilities, never ordinary scalar/class identifiers. Reject
        // their native use explicitly; generic parser errors are not a
        // trustworthy compiler admission boundary.
        if (token_.text == "SelectPlan" || token_.text == "SelectCase")
            fail("native SelectPlan/SelectCase ownership unsupported: "
                 "linear case tickets, cancellation and scheduler lowering required");
        if (token_.text == "ActorGroup" ||
            token_.text == "ActorGroupJoinCapability")
            fail("native ActorGroup source lowering unsupported: "
                 "typed membership capabilities and lifecycle authority required");
        if (token_.kind != Kind::Identifier || reserved(token_.text)) {
            fail("expected non-keyword identifier");
        }
        std::string result(token_.text);
        advance();
        return result;
    }

    static void validate_expression(const Expression& expr,
                                    const std::unordered_set<std::string>& parameters,
                                    const NativeSignatures& functions,
                                    std::size_t depth = 1) {
        require_expression_depth(depth, expr.offset);
        switch (expr.kind) {
            case Expression::Kind::Integer:
            case Expression::Kind::Boolean:
                return;
            case Expression::Kind::Variable:
                if (parameters.count(expr.name) == 0) {
                    throw std::runtime_error("unknown parameter '" + expr.name
                                             + "' at byte " + std::to_string(expr.offset));
                }
                return;
            case Expression::Kind::Field:
                validate_expression(*expr.left, parameters, functions, depth + 1);
                return;
            case Expression::Kind::ArrayCall:
                validate_expression(*expr.left, parameters, functions, depth + 1);
                for (const auto& arg : expr.arguments)
                    validate_expression(*arg, parameters, functions, depth + 1);
                return;
            case Expression::Kind::Call: {
                const auto found = functions.find(expr.name);
                if (found == functions.end() ||
                    found->second.count(expr.arguments.size()) == 0) {
                    throw std::runtime_error("unknown function or wrong call arity '"
                                             + expr.name + "' at byte "
                                             + std::to_string(expr.offset));
                }
                if (!expr.argument_names.empty() &&
                    expr.argument_names.size() != expr.arguments.size())
                    throw std::runtime_error("invalid named call argument layout");
                const auto& signature = found->second.at(expr.arguments.size());
                const auto& formals = signature.parameters;
                if (expr.type_arguments_present && signature.generics.empty())
                    throw std::runtime_error("call-site type arguments require generic function");
                if (!signature.generics.empty()) {
                    std::unordered_set<std::string> inferred;
                    std::unordered_set<std::string> assigned;
                    std::unordered_set<std::string> holes;
                    std::size_t positional_type = 0;
                    bool saw_named_type = false;
                    if (expr.type_arguments.size() > signature.generics.size())
                        throw std::runtime_error("too many generic type arguments");
                    for (const auto& arg : expr.type_arguments) {
                        std::size_t slot = positional_type;
                        if (!arg.name.empty()) {
                            saw_named_type = true;
                            auto it = std::find_if(signature.generics.begin(), signature.generics.end(),
                                [&](const GenericParameter& candidate) { return candidate.name == arg.name; });
                            if (it == signature.generics.end())
                                throw std::runtime_error("unknown named generic argument '" + arg.name + "'");
                            slot = static_cast<std::size_t>(it - signature.generics.begin());
                        } else {
                            if (saw_named_type)
                                throw std::runtime_error("positional generic argument after named argument");
                            ++positional_type;
                        }
                        const std::string& name = signature.generics.at(slot).name;
                        if (!assigned.insert(name).second)
                            throw std::runtime_error("duplicate generic argument '" + name + "'");
                        if (arg.infer) holes.insert(name);
                        else if (arg.type == "int") inferred.insert(name);
                        else throw std::runtime_error("unsupported native generic type specialization");
                    }
                    // Every native scalar value argument is i64. Only slots that
                    // actually appear in a scalar formal may be inferred.
                    for (const auto& type : signature.parameter_generic_types)
                        if (!type.empty()) inferred.insert(type);
                    for (const auto& slot : signature.generics) {
                        if (inferred.count(slot.name)) continue;
                        if (!holes.count(slot.name) && !slot.default_type.empty()) {
                            if (slot.default_type == "int" || inferred.count(slot.default_type)) {
                                inferred.insert(slot.name);
                                continue;
                            }
                            throw std::runtime_error("cannot resolve dependent default for generic '" + slot.name + "'");
                        }
                        throw std::runtime_error("cannot infer generic parameter '" + slot.name + "'");
                    }
                }
                std::vector<bool> bound(formals.size(), false);
                bool saw_named = false;
                std::size_t positional = 0;
                for (std::size_t i = 0; i < expr.arguments.size(); ++i) {
                    const std::string label = expr.argument_names.empty()
                        ? std::string{} : expr.argument_names[i];
                    std::size_t slot = positional;
                    if (!label.empty()) {
                        saw_named = true;
                        auto it = std::find(formals.begin(), formals.end(), label);
                        if (it == formals.end())
                            throw std::runtime_error("unknown named argument '" + label
                                                     + "' for " + expr.name);
                        slot = static_cast<std::size_t>(it - formals.begin());
                    } else {
                        if (saw_named)
                            throw std::runtime_error("positional argument after named argument");
                        ++positional;
                    }
                    if (slot >= formals.size() || bound[slot])
                        throw std::runtime_error("duplicate binding of parameter '"
                                                 + formals[slot] + "'");
                    bound[slot] = true;
                    // Check nested calls in lexical order, not formal-slot order.
                    validate_expression(*expr.arguments[i], parameters, functions, depth + 1);
                }
                return;
            }
            case Expression::Kind::Negate:
            case Expression::Kind::LogicalNot:
            case Expression::Kind::Copy:
            case Expression::Kind::Take:
            case Expression::Kind::Share:
            case Expression::Kind::Borrow:
            case Expression::Kind::BorrowMut:
                validate_expression(*expr.left, parameters, functions, depth + 1);
                return;
            case Expression::Kind::Add:
            case Expression::Kind::Subtract:
            case Expression::Kind::Multiply:
            case Expression::Kind::Equal:
            case Expression::Kind::NotEqual:
            case Expression::Kind::Less:
            case Expression::Kind::LessEqual:
            case Expression::Kind::Greater:
            case Expression::Kind::GreaterEqual:
            case Expression::Kind::LogicalAnd:
            case Expression::Kind::LogicalOr:
            case Expression::Kind::LogicalXor:
                validate_expression(*expr.left, parameters, functions, depth + 1);
                validate_expression(*expr.right, parameters, functions, depth + 1);
                return;
            case Expression::Kind::Conditional:
                if (expr.condition)
                    validate_expression(*expr.condition, parameters, functions, depth + 1);
                else {
                    validate_expression(*expr.condition_lhs, parameters, functions, depth + 1);
                    validate_expression(*expr.condition_rhs, parameters, functions, depth + 1);
                }
                validate_expression(*expr.when_true, parameters, functions, depth + 1);
                validate_expression(*expr.when_false, parameters, functions, depth + 1);
                return;
        }
        throw std::runtime_error("internal invalid expression kind");
    }

    static const char* scalar_type_name(ScalarType type) {
        return type == ScalarType::Bool ? "bool" : "int";
    }

    static ScalarType validate_scalar_expression(
            const Expression& expr,
            const std::unordered_map<std::string, ScalarType>& variables,
            const NativeSignatures& functions,
            std::size_t depth = 1) {
        require_expression_depth(depth, expr.offset);
        auto require_type = [&](ScalarType actual, ScalarType expected, std::string_view where) {
            if (actual != expected)
                throw std::runtime_error(std::string(where) + " requires " +
                    scalar_type_name(expected) + ", got " + scalar_type_name(actual) +
                    " at byte " + std::to_string(expr.offset));
        };
        switch (expr.kind) {
            case Expression::Kind::Integer:
                return ScalarType::I64;
            case Expression::Kind::Boolean:
                return ScalarType::Bool;
            case Expression::Kind::Variable: {
                const auto found = variables.find(expr.name);
                // Non-scalar class/array/actor names are rejected by ownership
                // if used as scalar values; treat their placeholder as i64 here
                // only so field/borrow syntax can reach that stricter pass.
                return found == variables.end() ? ScalarType::I64 : found->second;
            }
            case Expression::Kind::Field:
                return ScalarType::I64;
            case Expression::Kind::ArrayCall: {
                const std::size_t expected = expr.name == "get" || expr.name == "add"
                    ? 1 : expr.name == "set" ? 2 : 0;
                if (expected == 0 || expr.arguments.size() != expected)
                    throw std::runtime_error("unsupported native Array<int> scalar operation at byte " +
                        std::to_string(expr.offset));
                for (const auto& argument : expr.arguments) {
                    const ScalarType actual = validate_scalar_expression(
                        *argument, variables, functions, depth + 1);
                    if (actual != ScalarType::I64)
                        throw std::runtime_error("native Array<int> arguments require int, got " +
                            std::string(scalar_type_name(actual)) + " at byte " +
                            std::to_string(argument->offset));
                }
                return ScalarType::I64;
            }
            case Expression::Kind::Call: {
                const auto family = functions.find(expr.name);
                if (family == functions.end() ||
                        !family->second.count(expr.arguments.size()))
                    throw std::runtime_error("unknown function or wrong call arity '" +
                        expr.name + "' at byte " + std::to_string(expr.offset));
                const auto& signature = family->second.at(expr.arguments.size());
                std::vector<bool> bound(signature.parameters.size(), false);
                std::size_t positional = 0;
                bool named_seen = false;
                for (std::size_t i = 0; i < expr.arguments.size(); ++i) {
                    const std::string label = expr.argument_names.empty()
                        ? std::string{} : expr.argument_names[i];
                    std::size_t slot = positional;
                    if (!label.empty()) {
                        named_seen = true;
                        auto it = std::find(signature.parameters.begin(),
                            signature.parameters.end(), label);
                        if (it == signature.parameters.end())
                            throw std::runtime_error("unknown named argument '" + label + "'");
                        slot = static_cast<std::size_t>(it - signature.parameters.begin());
                    } else {
                        if (named_seen)
                            throw std::runtime_error("positional argument after named argument");
                        ++positional;
                    }
                    if (slot >= signature.parameters.size() || bound[slot])
                        throw std::runtime_error("duplicate native scalar argument binding");
                    bound[slot] = true;
                    const ScalarType actual = validate_scalar_expression(
                        *expr.arguments[i], variables, functions, depth + 1);
                    if (signature.parameter_is_scalar[slot] &&
                            actual != signature.parameter_scalar_types[slot])
                        throw std::runtime_error("argument '" + signature.parameters[slot] +
                            "' requires " + scalar_type_name(signature.parameter_scalar_types[slot]) +
                            ", got " + scalar_type_name(actual) + " at byte " +
                            std::to_string(expr.arguments[i]->offset));
                }
                // Ownership/expression-context admission separately rejects
                // a void call where a scalar value is required. Returning the
                // placeholder here lets statement-position void calls reuse the
                // exact same typed argument binder.
                return signature.returns_void ? ScalarType::I64 : signature.return_type;
            }
            case Expression::Kind::Negate: {
                ScalarType operand = validate_scalar_expression(
                    *expr.left, variables, functions, depth + 1);
                require_type(operand, ScalarType::I64, "unary '-'");
                return ScalarType::I64;
            }
            case Expression::Kind::LogicalNot: {
                ScalarType operand = validate_scalar_expression(
                    *expr.left, variables, functions, depth + 1);
                require_type(operand, ScalarType::Bool, "logical '!'");
                return ScalarType::Bool;
            }
            case Expression::Kind::Copy:
            case Expression::Kind::Take:
            case Expression::Kind::Share:
            case Expression::Kind::Borrow:
            case Expression::Kind::BorrowMut:
                return validate_scalar_expression(
                    *expr.left, variables, functions, depth + 1);
            case Expression::Kind::Add:
            case Expression::Kind::Subtract:
            case Expression::Kind::Multiply: {
                ScalarType left = validate_scalar_expression(
                    *expr.left, variables, functions, depth + 1);
                ScalarType right = validate_scalar_expression(
                    *expr.right, variables, functions, depth + 1);
                require_type(left, ScalarType::I64, "arithmetic");
                require_type(right, ScalarType::I64, "arithmetic");
                return ScalarType::I64;
            }
            case Expression::Kind::Less:
            case Expression::Kind::LessEqual:
            case Expression::Kind::Greater:
            case Expression::Kind::GreaterEqual: {
                ScalarType left = validate_scalar_expression(
                    *expr.left, variables, functions, depth + 1);
                ScalarType right = validate_scalar_expression(
                    *expr.right, variables, functions, depth + 1);
                require_type(left, ScalarType::I64, "ordered comparison");
                require_type(right, ScalarType::I64, "ordered comparison");
                return ScalarType::Bool;
            }
            case Expression::Kind::Equal:
            case Expression::Kind::NotEqual: {
                ScalarType left = validate_scalar_expression(
                    *expr.left, variables, functions, depth + 1);
                ScalarType right = validate_scalar_expression(
                    *expr.right, variables, functions, depth + 1);
                if (left != right)
                    throw std::runtime_error("equality operands require the same scalar type at byte " +
                        std::to_string(expr.offset));
                return ScalarType::Bool;
            }
            case Expression::Kind::LogicalAnd:
            case Expression::Kind::LogicalOr:
            case Expression::Kind::LogicalXor: {
                ScalarType left = validate_scalar_expression(
                    *expr.left, variables, functions, depth + 1);
                ScalarType right = validate_scalar_expression(
                    *expr.right, variables, functions, depth + 1);
                require_type(left, ScalarType::Bool, "logical operator");
                require_type(right, ScalarType::Bool, "logical operator");
                return ScalarType::Bool;
            }
            case Expression::Kind::Conditional: {
                ScalarType condition = expr.condition
                    ? validate_scalar_expression(*expr.condition, variables, functions, depth + 1)
                    : ScalarType::Bool;
                require_type(condition, ScalarType::Bool, "ternary condition");
                ScalarType when_true = validate_scalar_expression(
                    *expr.when_true, variables, functions, depth + 1);
                ScalarType when_false = validate_scalar_expression(
                    *expr.when_false, variables, functions, depth + 1);
                if (when_true != when_false)
                    throw std::runtime_error("ternary arms require a common scalar type at byte " +
                        std::to_string(expr.offset));
                return when_true;
            }
        }
        throw std::runtime_error("internal invalid native scalar expression kind");
    }

    static bool contains_callable_invocation(
            const Expression* expr, std::size_t depth = 1) {
        if (!expr) return false;
        require_expression_depth(depth, expr->offset);
        if (expr->kind == Expression::Kind::Call ||
            expr->kind == Expression::Kind::ArrayCall) return true;
        if (contains_callable_invocation(expr->left.get(), depth + 1) ||
            contains_callable_invocation(expr->right.get(), depth + 1) ||
            contains_callable_invocation(expr->condition.get(), depth + 1) ||
            contains_callable_invocation(expr->condition_lhs.get(), depth + 1) ||
            contains_callable_invocation(expr->condition_rhs.get(), depth + 1) ||
            contains_callable_invocation(expr->when_true.get(), depth + 1) ||
            contains_callable_invocation(expr->when_false.get(), depth + 1)) return true;
        for (const auto& item : expr->arguments)
            if (contains_callable_invocation(item.get(), depth + 1)) return true;
        return false;
    }

    static void validate_declared_effects(const Function& fn, const Program& program) {
        if (!fn.has_declared_effects) return;
        if (!fn.actor_spawns.empty() || !fn.ready_await_actor.empty()
                || !fn.actor_sends.empty() || !fn.actor_stops.empty()
                || !fn.actor_field_writes.empty()) {
            throw std::runtime_error(
                "using effects on actor actions requires trusted actor effect summaries");
        }
        for (const auto& local : fn.locals) {
            bool allocates_class_array = false;
            if (local.new_class) {
                const auto cls = std::find_if(program.classes.begin(), program.classes.end(),
                    [&](const ClassDecl& item) { return item.name == local.class_name; });
                if (cls != program.classes.end())
                    allocates_class_array = std::any_of(cls->fields.begin(), cls->fields.end(),
                        [](const ClassField& field) { return field.int_array; });
            }
            if ((local.heap_owned || local.int_array || allocates_class_array) &&
                    !fn.declared_effects.has(FunctionEffect::Alloc))
                throw std::runtime_error(
                    "owned allocation violates using signature without alloc");
            if (contains_callable_invocation(local.initializer.get()))
                throw std::runtime_error(
                    "using effects require transitive call summaries");
        }
        std::function<void(const Statement&)> validate_statement_effects;
        validate_statement_effects = [&](const Statement& stmt) {
            if (stmt.kind == Statement::Kind::ArrayCommitFilled &&
                    !fn.declared_effects.has(FunctionEffect::Alloc))
                throw std::runtime_error("filled array commit violates using signature without alloc");
            if (contains_callable_invocation(stmt.expression.get()) ||
                    contains_callable_invocation(stmt.condition.get()) ||
                    contains_callable_invocation(stmt.condition_lhs.get()) ||
                    contains_callable_invocation(stmt.condition_rhs.get()) ||
                    (stmt.branch_local &&
                        contains_callable_invocation(stmt.branch_local->initializer.get())))
                throw std::runtime_error("using effects require transitive call summaries");
            if (stmt.kind == Statement::Kind::ArrayCommitFilled) {
                if (contains_callable_invocation(stmt.array_commit_length.get()))
                    throw std::runtime_error("filled array commit requires pure scalar length");
                for (const auto& item : stmt.array_commit_values)
                    if (contains_callable_invocation(item.get()))
                        throw std::runtime_error("filled array commit requires pure scalar fills");
            }
            for (const auto& nested : stmt.then_statements)
                validate_statement_effects(nested);
            for (const auto& nested : stmt.else_statements)
                validate_statement_effects(nested);
        };
        for (const auto& stmt : fn.statements)
            validate_statement_effects(stmt);
        for (const auto& guard : fn.early_returns)
            if (contains_callable_invocation(guard.condition.get()) ||
                contains_callable_invocation(guard.lhs.get()) ||
                contains_callable_invocation(guard.rhs.get()) ||
                contains_callable_invocation(guard.result.get()))
                throw std::runtime_error("using effects require transitive call summaries");
        if (contains_callable_invocation(fn.result.get()))
            throw std::runtime_error("using effects require transitive call summaries");
    }

    static void validate(const Program& program) {
        std::unordered_set<std::string> classes;
        for (const auto& cls : program.classes) {
            if (!classes.insert(cls.name).second)
                throw std::runtime_error("duplicate class declaration '" + cls.name + "'");
        }
        NativeSignatures functions;
        for (const auto& fn : program.functions) {
            validate_declared_effects(fn, program);
            // Compiler-owned ABI imports and generated exports may be
            // inserted before source function definitions. LLVM silently
            // renames duplicate Function::Create symbols (e.g. abort.1),
            // which would disconnect source identity from the actual linker
            // symbol. Reject unqualified native/wasm symbols *before* IR.
            // Module-qualified names are emitted as module.name and do not
            // collide at the ELF/Wasm symbol boundary.
            if (fn.name.rfind("oreslang_int_array_", 0) == 0)
                throw std::runtime_error("reserved native Array<int> ABI symbol");
            if (program.module.empty()) {
                const bool runtime_symbol =
                    fn.name == "abort" ||
                    fn.name == "__oreslang_guest_main" ||
                    fn.name.rfind("oreslang_owned_i64_region_", 0) == 0 ||
                    fn.name.rfind("ores_actor_i64_heap_", 0) == 0 ||
                    fn.name.rfind("oreslang_source_actor_", 0) == 0 ||
                    fn.name.rfind("oreslang_actor_", 0) == 0 ||
                    fn.name.rfind("oreslang_wasm_actor_", 0) == 0;
                if (runtime_symbol)
                    throw std::runtime_error("reserved native runtime ABI symbol '" +
                        fn.name + "'");
            }
            if (std::count_if(fn.locals.begin(), fn.locals.end(),
                    [](const LocalBinding& local) { return local.int_array; }) > 64)
                throw std::runtime_error("native Array<int> owner limit exceeded (64)");
            // Overload identity is strictly name and explicit argument count.
            // Parameter types, borrow modes and returns cannot select a slot.
            if (fn.name == "main" && functions.count("main"))
                throw std::runtime_error("duplicate native entrypoint 'main'; main cannot be overloaded");
            NativeCallSignature signature;
            signature.generics = fn.generic_parameters;
            for (const auto& param : fn.parameters) {
                signature.parameters.push_back(param.name);
                signature.parameter_generic_types.push_back(param.generic_type);
                signature.parameter_scalar_types.push_back(param.scalar_type);
                signature.parameter_is_scalar.push_back(
                    param.class_name.empty() && param.generic_type.empty());
            }
            signature.return_type = fn.return_type;
            signature.returns_void = fn.returns_void;
            if (!functions[fn.name].emplace(fn.parameters.size(), std::move(signature)).second)
                throw std::runtime_error("function '" + fn.name + "' already has arity "
                    + std::to_string(fn.parameters.size()) +
                    "; parameter and return types do not select overloads at byte " +
                    std::to_string(fn.offset));
            std::unordered_set<std::string> params;
            for (const auto& param : fn.parameters) {
                if (!params.insert(param.name).second) {
                    throw std::runtime_error("duplicate parameter '" + param.name
                                             + "' at byte " + std::to_string(fn.offset));
                }
            }
        }
        std::unordered_map<std::string, const ActorDecl*> actor_types;
        for (const auto& actor : program.actors) {
            if (!actor_types.emplace(actor.name, &actor).second || classes.count(actor.name)
                    || functions.count(actor.name))
                throw std::runtime_error("duplicate actor/class/function symbol '" + actor.name + "'");
            std::unordered_set<std::string> fields;
            std::unordered_set<std::string> mutables;
            const std::unordered_set<std::string> empty;
            for (const auto& field : actor.fields) {
                validate_expression(*field.initializer, empty, {});
                if (!fields.insert(field.name).second)
                    throw std::runtime_error("duplicate actor field '" + field.name + "'");
                if (field.field_mutable) mutables.insert(field.name);
            }
            const auto& method = actor.on_message;
            const std::string& parameter = method.parameters.at(0).name;
            if (!fields.insert(parameter).second)
                throw std::runtime_error("actor parameter shadows field '" + parameter + "'");
            for (const auto& write : method.actor_field_writes) {
                if (!mutables.count(write.field))
                    throw std::runtime_error("cannot assign immutable or unknown actor field '" + write.field + "'");
                validate_expression(*write.expression, fields, functions);
            }
            validate_expression(*method.result, fields, functions);
            if (functions.count("oreslang_actor_" + actor.name + "_init") ||
                    functions.count("oreslang_actor_" + actor.name + "_on_message"))
                throw std::runtime_error("conflicting generated actor symbol");
        }
        for (const auto& fn : program.functions) {
            std::unordered_set<std::string> params;
            for (const auto& param : fn.parameters) params.insert(param.name);
            std::unordered_set<std::string> live_actors;
            std::unordered_map<std::string, const ActorDecl*> references;
            if (!fn.actor_spawns.empty() || !fn.ready_await_actor.empty()
                    || !fn.actor_sends.empty() || !fn.actor_stops.empty()) {
                if (fn.name != "main" || !fn.parameters.empty() || fn.returns_void ||
                        !fn.early_returns.empty() || !fn.statements.empty())
                    throw std::runtime_error(
                        "source actor bootstrap requires zero-argument int main without early returns");
            }
            for (const auto& spawn : fn.actor_spawns) {
                const auto it = actor_types.find(spawn.actor_type);
                if (it == actor_types.end())
                    throw std::runtime_error("unknown actor type '" + spawn.actor_type + "'");
                if (!live_actors.insert(spawn.name).second || params.count(spawn.name))
                    throw std::runtime_error("duplicate actor reference '" + spawn.name + "'");
                references.emplace(spawn.name, it->second);
            }
            if (!fn.ready_await_actor.empty()) {
                /*
                 * First executable suspension is deliberately tiny: one actor
                 * capability, no scalar/class/array owner crossing suspension,
                 * no defer/guard CFG, and no generic Future<T> representation.
                 */
                if (fn.actor_spawns.size() != 1 || !fn.locals.empty())
                    throw std::runtime_error(
                        "await get_ready requires one source actor without suspended locals");
                if (!live_actors.count(fn.ready_await_actor))
                    throw std::runtime_error(
                        "await get_ready requires a live direct ActorRef receiver");
                /*
                 * The first vertical slice evaluates the final scalar result on
                 * the root continuation before trusted-main stop. Keep that
                 * movement observationally inert until stop itself can be
                 * lowered into nonblocking continuation cleanup.
                 */
                if (contains_callable_invocation(fn.result.get()))
                    throw std::runtime_error(
                        "await get_ready requires side-effect-free scalar return");
            }
            for (const auto& binding : fn.locals) {
                // Validate before publishing the name: reject self/forward reads.
                if (binding.int_array) {
                    // Construction was parsed as exactly new Array<int>().
                    // Its initializer is a trusted runtime operation, never
                    // a user-callable scalar or transferable class value.
                } else if (binding.new_class || binding.move_class) {
                    if (classes.count(binding.class_name) == 0)
                        throw std::runtime_error("unknown class '" + binding.class_name + "'");
                    if (binding.move_class && !params.count(binding.move_source))
                        throw std::runtime_error("unknown class move source '" + binding.move_source + "'");
                    if (binding.new_class) {
                        const auto cls = std::find_if(program.classes.begin(), program.classes.end(),
                            [&](const ClassDecl& c) { return c.name == binding.class_name; });
                        if (cls == program.classes.end())
                            throw std::runtime_error("unknown class constructor");
                        if (binding.constructor_arguments.size() != cls->constructor_parameters.size())
                            throw std::runtime_error("native class constructor argument arity mismatch for '" +
                                                     cls->name + "'");
                        for (const auto& arg : binding.constructor_arguments) {
                            if (!native_constructor_scalar_tree(*arg))
                                throw std::runtime_error("native scalar constructor argument requires pure integer expression");
                            validate_expression(*arg, params, functions);
                        }
                    }
                } else {
                    validate_expression(*binding.initializer, params, functions);
                }
                if (live_actors.count(binding.name))
                    throw std::runtime_error("scalar local shadows actor reference '" + binding.name + "'");
                if (!params.insert(binding.name).second) {
                    throw std::runtime_error("duplicate owned local '" + binding.name
                        + "' at byte " + std::to_string(binding.offset));
                }
            }
            using NameSet = std::unordered_set<std::string>;
            std::function<void(const Statement&, NameSet&)> check_statement_names;
            check_statement_names = [&](const Statement& stmt, NameSet& names) {
                if (stmt.kind == Statement::Kind::Conditional) {
                    if (stmt.condition) validate_expression(*stmt.condition, names, functions);
                    else {
                        validate_expression(*stmt.condition_lhs, names, functions);
                        validate_expression(*stmt.condition_rhs, names, functions);
                    }
                    NameSet then_names = names;
                    NameSet else_names = names;
                    for (const auto& action : stmt.then_statements)
                        check_statement_names(action, then_names);
                    for (const auto& action : stmt.else_statements)
                        check_statement_names(action, else_names);
                    // Neither arm's lexical locals escape into the parent scope.
                    return;
                }
                if (stmt.kind == Statement::Kind::ArrayCommitFilled) {
                    if (!names.count(stmt.receiver))
                        throw std::runtime_error("unknown array commit class receiver '" +
                            stmt.receiver + "'");
                    validate_expression(*stmt.array_commit_length, names, functions);
                    for (const auto& item : stmt.array_commit_values)
                        validate_expression(*item, names, functions);
                    return;
                }
                if (stmt.kind == Statement::Kind::ScopedLocal) {
                    const LocalBinding& local = *stmt.branch_local;
                    validate_expression(*local.initializer, names, functions);
                    if (!names.insert(local.name).second)
                        throw std::runtime_error("duplicate branch-local name '" +
                            local.name + "' at byte " + std::to_string(local.offset));
                    return;
                }
                if ((stmt.kind == Statement::Kind::FieldWrite ||
                        (stmt.kind == Statement::Kind::Defer && !stmt.receiver.empty())) &&
                        !names.count(stmt.receiver))
                    throw std::runtime_error("unknown field receiver '" + stmt.receiver + "'");
                validate_expression(*stmt.expression, names, functions);
            };
            for (const auto& stmt : fn.statements)
                check_statement_names(stmt, params);
            // Validate effects in lexical order. In particular, a send after
            // stop must not pass by consulting the all-spawns reference map.
            for (const auto& action : fn.actor_actions) {
                if (action.kind == ActorAction::Kind::Send) {
                    const auto& send = fn.actor_sends.at(action.index);
                    if (!references.count(send.reference))
                        throw std::runtime_error("unknown actor message receiver '" + send.reference + "'");
                    if (!live_actors.count(send.reference))
                        throw std::runtime_error("actor message sent after stop '" + send.reference + "'");
                    validate_expression(*send.payload, params, functions);
                } else {
                    const auto& stop = fn.actor_stops.at(action.index);
                    if (!live_actors.erase(stop))
                        throw std::runtime_error("invalid or repeated actor stop '" + stop + "'");
                }
            }
            if (!live_actors.empty())
                throw std::runtime_error("actor reference must be stopped before return");
            if (fn.caught_throw_value) {
                // The thrown i64 expression and scoped defers see the original
                // lexical environment. Catch binding enters only on handler
                // admission, before checking the catch return expression.
                validate_expression(*fn.caught_throw_value, params, functions);
                for (const auto& defer : fn.caught_throw_defers)
                    check_statement_names(defer, params);
                if (!params.insert(fn.catch_binding).second)
                    throw std::runtime_error("duplicate caught throw binding '" +
                        fn.catch_binding + "'");
            }
            for (const auto& guard : fn.early_returns) {
                if (guard.condition) validate_expression(*guard.condition, params, functions);
                else {
                    validate_expression(*guard.lhs, params, functions);
                    validate_expression(*guard.rhs, params, functions);
                }
                validate_expression(*guard.result, params, functions);
            }
            if (fn.result) validate_expression(*fn.result, params, functions);

            // Independent scalar type pass. Name/arity validation above must
            // complete first so this pass never guesses through unresolved
            // symbols or accidentally turns a typing failure into binding.
            using TypeMap = std::unordered_map<std::string, ScalarType>;
            TypeMap scalar_types;
            for (const auto& param : fn.parameters)
                scalar_types.emplace(param.name, param.scalar_type);

            auto require_scalar = [&](const Expression& expr, ScalarType expected,
                                      const char* where, const TypeMap& env) {
                const ScalarType actual =
                    validate_scalar_expression(expr, env, functions);
                if (actual != expected)
                    throw std::runtime_error(std::string(where) + " requires " +
                        scalar_type_name(expected) + ", got " +
                        scalar_type_name(actual) + " at byte " +
                        std::to_string(expr.offset));
            };

            for (const auto& binding : fn.locals) {
                if (binding.int_array || binding.new_class || binding.move_class) {
                    scalar_types.emplace(binding.name, ScalarType::I64);
                    if (binding.new_class) {
                        for (const auto& arg : binding.constructor_arguments)
                            require_scalar(*arg, ScalarType::I64,
                                "native class constructor argument", scalar_types);
                    }
                    continue;
                }
                require_scalar(*binding.initializer, binding.scalar_type,
                    "native scalar local initializer", scalar_types);
                scalar_types.emplace(binding.name, binding.scalar_type);
            }

            std::function<void(const Statement&, TypeMap&)> check_statement_types;
            check_statement_types = [&](const Statement& stmt, TypeMap& env) {
                if (stmt.kind == Statement::Kind::Conditional) {
                    if (stmt.condition) {
                        require_scalar(*stmt.condition, ScalarType::Bool,
                            "native if condition", env);
                    } else {
                        require_scalar(*stmt.condition_lhs, ScalarType::I64,
                            "native if equality left operand", env);
                        require_scalar(*stmt.condition_rhs, ScalarType::I64,
                            "native if equality right operand", env);
                    }
                    TypeMap then_env = env;
                    TypeMap else_env = env;
                    for (const auto& action : stmt.then_statements)
                        check_statement_types(action, then_env);
                    for (const auto& action : stmt.else_statements)
                        check_statement_types(action, else_env);
                    return;
                }
                if (stmt.kind == Statement::Kind::ArrayCommitFilled) {
                    require_scalar(*stmt.array_commit_length, ScalarType::I64,
                        "native array commit length", env);
                    for (const auto& item : stmt.array_commit_values)
                        require_scalar(*item, ScalarType::I64,
                            "native array commit fill", env);
                    return;
                }
                if (stmt.kind == Statement::Kind::ScopedLocal) {
                    const LocalBinding& local = *stmt.branch_local;
                    require_scalar(*local.initializer, local.scalar_type,
                        "native branch-local initializer", env);
                    env.emplace(local.name, local.scalar_type);
                    return;
                }
                if (stmt.kind == Statement::Kind::FieldWrite ||
                        (stmt.kind == Statement::Kind::Defer && !stmt.receiver.empty())) {
                    require_scalar(*stmt.expression, ScalarType::I64,
                        "native class field write", env);
                    return;
                }
                if (stmt.expression)
                    (void)validate_scalar_expression(*stmt.expression, env, functions);
            };
            for (const auto& stmt : fn.statements)
                check_statement_types(stmt, scalar_types);

            for (const auto& action : fn.actor_actions) {
                if (action.kind == ActorAction::Kind::Send)
                    require_scalar(*fn.actor_sends.at(action.index).payload,
                        ScalarType::I64, "native actor message payload", scalar_types);
            }
            if (fn.caught_throw_value)
                require_scalar(*fn.caught_throw_value, ScalarType::I64,
                    "native thrown scalar", scalar_types);
            for (const auto& guard : fn.early_returns) {
                if (guard.condition) {
                    require_scalar(*guard.condition, ScalarType::Bool,
                        "native guarded return condition", scalar_types);
                } else {
                    require_scalar(*guard.lhs, ScalarType::I64,
                        "native return guard left operand", scalar_types);
                    require_scalar(*guard.rhs, ScalarType::I64,
                        "native return guard right operand", scalar_types);
                }
                require_scalar(*guard.result, fn.return_type,
                    "native guarded return", scalar_types);
            }
            if (fn.result && !fn.returns_void)
                require_scalar(*fn.result, fn.return_type,
                    "native function return", scalar_types);
        }
    }

    void advance() { token_ = lexer_.next(); }
    [[noreturn]] void fail(const std::string& reason) const {
        if (token_.text == "gpu" || token_.text == "cpu" || token_.text == "quantum") {
            const char* domain = token_.text == "cpu"
                    ? "native cpu execution block boundary unsupported: "
                    : "native gpu/quantum boundary unsupported: ";
            throw std::runtime_error(std::string(domain)
                    + "validated effects, ownership, host/device lowering and explicit "
                    "backend admission required; nested cpu/gpu blocks cannot "
                    "silently execute on the host; no implicit CPU fallback at byte "
                    + std::to_string(token_.offset));
        }
        if (token_.text == "throw" || token_.text == "raise" ||
            token_.text == "panic" || token_.text == "recover") {
            throw std::runtime_error(
                "native exceptional-control boundary unsupported: "
                "typed unwind, defer/finally, recover and supervisor lowering required"
                " at byte " + std::to_string(token_.offset));
        }
        // Syntactic admission is a safety boundary: rejecting actor/channel
        // keywords also means no unverified borrowed reference can enter a
        // suspended/native execution frame or deferred callback.
        if (token_.text == "select" || token_.text == "nb-select" ||
            token_.text == "readch" || token_.text == "writech" ||
            token_.text == "nb" ||
            token_.text == "do" || token_.text == "await" ||
            token_.text == "yield" || token_.text == "cooperate") {
            throw std::runtime_error(
                "native async/channel/select boundary unsupported: "
                "suspension-aware borrow checking and cancellation/actor lowering required"
                " at byte " + std::to_string(token_.offset));
        }
        throw std::runtime_error(reason + " at byte " + std::to_string(token_.offset));
    }

    std::string_view source_;
    Lexer lexer_;
    Token token_;
    bool experimental_owned_int_;
    bool experimental_rt_scalar_loop_;
    std::size_t expression_depth_ = 0;
    std::size_t conditional_depth_ = 0;
};

} // namespace

Program parse_program(std::string_view source, bool experimental_owned_int,
                      bool experimental_rt_scalar_loop) {
    Program program = Parser(source, experimental_owned_int,
                             experimental_rt_scalar_loop).parse();
    if (experimental_rt_scalar_loop &&
            (program.functions.size() != 1 || !program.functions.front().scalar_while ||
             !program.classes.empty() || !program.actors.empty() ||
             !program.module.empty()))
        throw std::runtime_error("experimental scalar loop requires exactly one standalone function");
    // LLVM lowering is prohibited unless the native subset has passed both
    // name/arity checking and flow-sensitive explicit ownership admission.
    validate_i64_ownership(program);
    return program;
}

} // namespace oreslang
