package dev.oreslang.runtime.compute;

import java.util.List;

/**
 * Explicit accelerator provider bootstrap surface.
 *
 * <p>Providers are supplied directly by trusted runtime/bootstrap code. The
 * registry intentionally performs no classpath scanning, reflection, or
 * ServiceLoader discovery so strict AOT builds remain closed-world and
 * tree-shakeable.</p>
 */
public interface ComputeProvider {
    String backendId();

    List<ComputeDevice> devices();
}
