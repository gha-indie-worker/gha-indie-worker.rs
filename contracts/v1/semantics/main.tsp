import "@typespec/json-schema";
using TypeSpec.JsonSchema;

// Semantic conformance is independent of backend implementation.
// An unsupported backend is pending, never a silently successful test.
@jsonSchema
namespace Oreslang.Conformance.SemanticsV1 {
  model BackendSupport {
    llvm: "required" | "pending";
    graal: "required" | "pending";
  }
  model SemanticCase {
    id: string;
    feature: string;
    source: string;
    kind: "native_exit" | "diagnostic";
    expected_exit?: int32;
    diagnostic_class?: "unknown_name" | "call_arity" | "duplicate_declaration" | "integer_overflow" | "unterminated_comment" | "ownership_violation";
    backends: BackendSupport;
    pending_reason?: string;
  }
  model Suite {
    schema_version: "1.1.0";
    cases: SemanticCase[];
  }
  model SemanticResult {
    id: string;
    status: "pass" | "fail" | "pending";
    observed_exit?: int32;
    diagnostic_class?: string;
    detail?: string;
  }
  model BackendReport {
    schema_version: "1.1.0";
    backend: "llvm" | "graal";
    suite_sha256: string;
    source_revision: string;
    results: SemanticResult[];
  }
}
