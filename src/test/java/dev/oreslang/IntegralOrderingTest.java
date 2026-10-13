package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IntegralOrderingTest {
    @Test
    void adjacentInt64ValuesRemainOrderedBeyondDoublePrecision() {
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).build()) {
            assertTrue(context.eval(OresLanguage.ID, """
                    pub routine main(): bool {
                      return 9007199254740993 > 9007199254740992
                        && 999999999999999999 < 1000000000000000000
                        && -999999999999999999 > -1000000000000000000
                        && 9223372036854775807 > 9223372036854775806
                        && (-9223372036854775807 - 1) < -9223372036854775807;
                    }
                    """).asBoolean());
        }
    }
}
