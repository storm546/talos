package jumpscan.app;

import java.util.List;
import jumpscan.core.Frame;
import jumpscan.core.JumpAnalyzer;
import jumpscan.core.JumpMetrics;

/** Keeps the app on the core detector until core exposes its landing details as a public Kotlin API. */
final class CoreLanding {
    final JumpMetrics metrics;
    final Long contactMs;

    CoreLanding(List<Frame> frames, String test, float aspect) {
        JumpAnalyzer.Analysis result = JumpAnalyzer.INSTANCE.analyzeWithLanding$core(frames, test, aspect, JumpAnalyzer.View.AUTO);
        metrics = result.getMetrics();
        contactMs = result.getContactMs();
    }
}
