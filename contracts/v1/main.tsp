import "@typespec/json-schema";
using TypeSpec.JsonSchema;

// Data interchange only: this does NOT specify the Oreslang grammar or runtime semantics.
@jsonSchema
namespace Oreslang.Conformance.V1 {
  model BackendMatrix {
    llvm: "required" | "pending";
    graal: "required" | "pending";
  }

  model ContractCase {
    id: string;
    feature: string;
    file: string;
    admission: "accept" | "reject";
    backends: BackendMatrix;
    pending_reason?: string;
  }

  model Suite {
    schema_version: "1.0.0";
    java_reference: string;
    llvm_reference: string;
    cases: ContractCase[];
  }

  model CaseResult {
    id: string;
    backend: "llvm" | "graal";
    status: "pass" | "fail" | "pending";
    observed_exit_code?: int32;
    diagnostic?: string;
  }

  model Report {
    schema_version: "1.0.0";
    backend: "llvm" | "graal";
    results: CaseResult[];
  }
}
