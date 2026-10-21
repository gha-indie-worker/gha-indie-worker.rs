import json
from pathlib import Path

root = Path(__file__).resolve().parent
source = (root / "stdlib" / "compute.ores").read_text(encoding="utf-8")
contract = json.loads((root / "contract" / "portable-compute-stdlib.json").read_text(encoding="utf-8"))

assert "define module compute" in source
assert "DeviceCapabilities" in source
assert "BufferLayout" in source
assert "DispatchShape" in source
assert "KernelRequirements" in source
assert "java:" not in source
assert "do java" not in source
assert "ServiceLoader" not in source

assert contract["namespace"] == "std/compute"
assert contract["providerDiscovery"] == "explicit-registration"
assert contract["implicitTransfers"] is False
assert contract["implicitCpuFallback"] is False
assert contract["runtimeReflectionRequired"] is False
assert contract["cancellationImpliesQuiescence"] is False
assert set(contract["sealedRuntimeHandles"]) == {"Device", "Buffer<T>", "Fence", "KernelContext"}

print("portable-compute-contract-smoke-ok")
