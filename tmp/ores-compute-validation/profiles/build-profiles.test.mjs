import test from "node:test";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const contractUrl = new URL("../build-profiles/v1.json", import.meta.url);

async function load() {
  return JSON.parse(await readFile(contractUrl, "utf8"));
}

test("build-profile v1 inheritance and reflection policy are fail-closed", async () => {
  const contract = await load();
  assert.equal(contract.schemaVersion, 1);
  const byName = new Map(contract.profiles.map(profile => [profile.name, profile]));
  assert.deepEqual([...byName.keys()], ["dev", "release", "strict-aot", "hard-rt"]);
  for (const profile of byName.values()) {
    assert.equal(profile.runtimeReflection, "forbidden");
  }
  assert.deepEqual(byName.get("release").implies, ["dev"]);
  assert.deepEqual(byName.get("strict-aot").implies, ["release"]);
  assert.deepEqual(byName.get("hard-rt").implies, ["strict-aot"]);
  assert.equal(byName.get("strict-aot").fullAot, true);
  assert.equal(byName.get("strict-aot").jit, "forbidden");
  assert.equal(byName.get("hard-rt").gc, "forbidden");
  assert.equal(byName.get("hard-rt").wcetEvidence, "required");
  assert.equal(contract.constraints.freestanding.libcDependency, "forbidden");
  const accelerators = contract.constraints.accelerators;
  assert.equal(accelerators.providerDiscovery, "explicit-only");
  assert.equal(accelerators.implicitTransfers, "forbidden");
  assert.equal(accelerators.implicitCpuFallback, "forbidden");
  assert.equal(accelerators.sourceForgeableAuthority, "forbidden");
  assert.equal(accelerators.cancellationQuiescence, "verified-fence-only");
  assert.equal(accelerators.strictAotKernelVariants, "precompiled-only");
  assert.equal(accelerators.hardRtResourceBounds, "required");
});
