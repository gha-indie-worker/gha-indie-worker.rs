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
assert kernel["cpuReferenceProvider"]["wholeKernelHostBufferLeaseRequired"] is True
assert kernel["cpuReferenceProvider"]["concurrentHostMutation"] == "serialized-during-execution"
assert kernel["gpuLowering"]["emissionIsExecutionEvidence"] is False
assert kernel["gpuLowering"]["providerAuthenticationRequired"] is True
assert kernel["gpuLowering"]["javaEmitterImplemented"] is True
assert kernel["gpuLowering"]["nativeEmitterImplemented"] is True
assert kernel["gpuLowering"]["exactArtifactParityRequired"] is True
assert kernel["promotion"]["requiresJavaNativeOpenClArtifactParity"] is True
print("portable-compute-kernel-g2-contract-ok")

provider = json.loads((root / "contract" / "portable-compute-provider.json").read_text(encoding="utf-8"))
assert provider["gate"] == "G3"
assert provider["trustedRuntimeOnly"] is True
assert provider["sourceSyntaxEnabled"] is False
assert provider["implicitCpuFallback"] is False
assert provider["physicalProvider"]["backend"] == "opencl-1.2"
assert provider["physicalProvider"]["guestCanLoadProvider"] is False
assert provider["physicalProvider"]["opaquePhysicalAllocationCapabilities"] is True
assert provider["quotas"]["reserveBeforeFenceClaim"] is True
assert provider["quotas"]["residentReleaseAfterPhysicalRelease"] is True
assert provider["submissionRules"]["markSubmittedOnlyAfterDurableEnqueue"] is True
assert provider["submissionRules"]["preEnqueueFailureUsesExactAbort"] is True
assert provider["submissionRules"]["submittedWorkCannotUseAbort"] is True
assert provider["submissionRules"]["cancellationImpliesQuiescence"] is False
assert provider["submissionRules"]["timeoutDoesNotFreeDeviceMemory"] is True
assert provider["testing"]["mockProviderSuccessIsNotGpuExecutionEvidence"] is True
assert provider["testing"]["realGpuExecutionEvidenceRequiresDeviceEvent"] is True
assert provider["physicalProvider"]["queueCapabilityBoundToDeviceAuthority"] is True
assert provider["physicalProvider"]["queueCapabilityGenerationRequired"] is True
assert provider["physicalProvider"]["artifactAbiValidationRequired"] is True
assert provider["physicalProvider"]["providerReleaseRetryable"] is True
assert provider["submissionRules"]["preEnqueueCleanupFailureRetainsRecoveryAuthority"] is True
assert provider["submissionRules"]["preEnqueueCleanupRetryRequired"] is True
assert provider["submissionRules"]["programReleaseFailureRetainsPreparedAuthority"] is True
assert provider["submissionRules"]["deviceLossDoesNotImplyQuiescence"] is True
assert provider["submissionRules"]["deviceLossRecoveryRequiresProviderProof"] is True
assert provider["submissionRules"]["deviceLossRetainsQuotaUntilRecovery"] is True
assert provider["submissionRules"]["providerRetainsSubmittedOperationAuthority"] is True
assert provider["submissionRules"]["callerHandleDropCannotReleasePhysicalResources"] is True
assert provider["testing"]["preEnqueueReleaseFailureRecoveryRequired"] is True
assert provider["testing"]["programReleaseRetryRequired"] is True
assert provider["testing"]["queueAuthorityForgeryRequired"] is True
assert provider["testing"]["deviceLossRecoveryFailureRetainsResources"] is True
assert provider["testing"]["deviceLossRecoverySuccessRequired"] is True
assert provider["testing"]["callerHandleDropRetentionRequired"] is True
assert provider["promotion"]["requiresArtifactAbiProviderValidation"] is True
assert provider["promotion"]["requiresRecoverablePreEnqueueCleanup"] is True
assert provider["promotion"]["requiresOpaqueJavaQueueAuthority"] is True
assert provider["promotion"]["requiresDeviceLossQuiescenceProof"] is True
assert provider["promotion"]["requiresProviderOwnedActiveOperationRegistry"] is True
print("portable-compute-provider-g3-contract-ok")
