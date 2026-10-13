import "@typespec/json-schema";
using TypeSpec.JsonSchema;

// Backend-neutral observational trace, not an assertion of runtime coverage.
@jsonSchema
namespace Oreslang.Conformance.ActorTraceV1 {
  model Event {
    seq: int32;
    kind: "spawn" | "admit" | "send" | "receive" | "exclusive_acquire" |
          "exclusive_release" | "terminate";
    actor: string;
    target?: string;
    message_id?: string;
    resource?: string;
  }
  model Trace {
    schema_version: "1.0.0";
    complete: boolean;
    events: Event[];
  }
}
