import json
from pathlib import Path

root = Path(__file__).resolve().parent
source = (root / "stdlib" / "compute.ores").read_text(encoding="utf-8")
contract = json.loads((root / "contract" / "portable-compute-stdlib.json").read_text(encoding="utf-8"))

assert "define module compute" in source
for type_name in ("DeviceCapabilities", "BufferLayout", "DispatchShape", "KernelRequirements"):
    assert type_name in source
for forbidden in ("java:", "do java", "ServiceLoader"):
    assert forbidden not in source

assert contract["namespace"] == "std/compute"
assert contract["providerDiscovery"] == "explicit-registration"
assert contract["implicitTransfers"] is False
assert contract["implicitCpuFallback"] is False
assert contract["runtimeReflectionRequired"] is False
assert contract["cancellationImpliesQuiescence"] is False
assert contract["descriptorIntegerDomain"] == "signed-i64"
assert contract["descriptorValidationScope"] == "portable-structural"
assert contract["providerValidationMayBeStricter"] is True
assert contract["checkedByteArithmeticBeforeAllocationOrTransfer"] is True
assert set(contract["sealedRuntimeHandles"]) == {"Device", "Buffer<T>", "Fence", "KernelContext"}

print("portable-compute-contract-hardening-ok")

authority = json.loads((root / "contract" / "portable-compute-authority.json").read_text(encoding="utf-8"))
assert authority["gate"] == "G1"
assert authority["sourceConstructibleHandles"] is False
assert authority["handles"]["Device"]["sealed"] is True
assert authority["handles"]["Buffer<T>"]["affineInSource"] is True
assert authority["handles"]["Fence"]["sealed"] is True
assert authority["transfers"]["operations"] == ["upload", "download"]
assert authority["transfers"]["explicitOnly"] is True
assert authority["transfers"]["implicitTransfer"] is False
assert authority["transfers"]["implicitCpuFallback"] is False
assert authority["transfers"]["crossDeviceForbidden"] is True
assert authority["transfers"]["crossOwnerDomainForbidden"] is True
assert authority["transfers"]["staleGenerationForbidden"] is True
assert authority["transfers"]["cancellationImpliesQuiescence"] is False
assert authority["transfers"]["reclaimRequiresVerifiedQuiescence"] is True
assert authority["transfers"]["preEnqueueAbortRequiresExactFence"] is True
assert authority["transfers"]["preEnqueueAbortRestoresIdle"] is True
assert authority["transfers"]["preEnqueueAbortForbiddenAfterSubmission"] is True
assert authority["authorityRules"]["numericIdsAreNotCapabilities"] is True
assert authority["authorityRules"]["metadataDescriptorsAreNotCapabilities"] is True
print("portable-compute-authority-g1-contract-ok")

kernel = json.loads((root / "contract" / "portable-compute-kernel.json").read_text(encoding="utf-8"))
assert kernel["gate"] == "G2"
assert kernel["sourceNeutralKernelIR"] is True
assert kernel["sourceSyntaxEnabled"] is False
assert kernel["sourceSyntaxGate"] == "fail-closed-until-typed-effects-and-lowering"
assert kernel["elementTypes"] == ["f32"]
assert kernel["operations"] == ["scale", "bias", "scale-bias"]
assert kernel["cpuReferenceProvider"]["explicitSelectionRequired"] is True
assert kernel["cpuReferenceProvider"]["implicitGpuFallback"] is False
assert kernel["cpuReferenceProvider"]["testingAndConformanceOnly"] is True
assert kernel["cpuReferenceProvider"]["floatingPointContraction"] == "forbidden-for-scale-bias-reference"
assert kernel["gpuLowering"]["emissionIsExecutionEvidence"] is False
assert kernel["gpuLowering"]["providerAuthenticationRequired"] is True
assert kernel["gpuLowering"]["javaEmitterImplemented"] is True
assert kernel["gpuLowering"]["nativeEmitterImplemented"] is True
assert kernel["gpuLowering"]["exactArtifactParityRequired"] is True
abi = kernel["gpuLowering"]["artifactAbi"]
assert abi["version"] == 1
assert abi["requiredAddressBits"] == 64
assert abi["requiredFloat32"] is True
assert abi["requiredLittleEndian"] is True
assert set(abi["providerMustValidate"]) == {
    "requiredBytes",
    "globalRows",
    "globalFields",
    "requiredAddressBits",
    "requiredFloat32",
    "requiredLittleEndian",
}
assert kernel["promotion"]["requiresJavaNativeOpenClArtifactParity"] is True
print("portable-compute-kernel-g2-contract-ok")

provider = json.loads((root / "contract" / "portable-compute-provider.json").read_text(encoding="utf-8"))
assert provider["gate"] == "G3"
assert provider["trustedRuntimeOnly"] is True
assert provider["sourceSyntaxEnabled"] is False
assert provider["implicitCpuFallback"] is False
rules = provider["submissionRules"]
assert rules["markSubmittedOnlyAfterDurableEnqueue"] is True
assert rules["preEnqueueFailureUsesExactAbort"] is True
assert rules["submittedWorkCannotUseAbort"] is True
assert rules["completionRequiresExactProviderEvent"] is True
assert rules["cancellationImpliesQuiescence"] is False
assert rules["deviceLossDoesNotImplyQuiescence"] is True
assert rules["deviceLossRecoveryRequiresProviderProof"] is True
assert rules["deviceLossRetainsQuotaUntilRecovery"] is True
assert rules["deviceLossDominatesCancellation"] is True
assert rules["lostDeviceEventRetainedUntilRecoveryProof"] is True
assert rules["cancelThenDeviceLossRequiresRecovery"] is True
assert provider["testing"]["mockProviderSuccessIsNotGpuExecutionEvidence"] is True
assert provider["testing"]["realGpuExecutionEvidenceRequiresDeviceEvent"] is True
print("portable-compute-provider-g3-contract-ok")
