package io.kestra.core.runners.pebble.functions;

import io.pebbletemplates.pebble.extension.Function;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class OpFunction implements Function {
  private static final long TIMEOUT_SECONDS = 10L;

  @Override
  public Object execute(Map<String, Object> args, PebbleTemplate self, EvaluationContext context, int lineNumber) {
    if (args == null || args.isEmpty()) {
      throw new IllegalArgumentException("op function requires a secret reference argument");
    }

    // We expect a single argument named "ref" (positional usage maps to it)
    Object refObj = args.values().iterator().next();
    if (refObj == null) {
      return null;
    }

    String ref = "op://" + refObj.toString();

    ProcessBuilder pb = new ProcessBuilder("op", "read", ref);
    pb.redirectErrorStream(false);

    try {
      Process p = pb.start();

      boolean finished = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!finished) {
        p.destroyForcibly();
        throw new RuntimeException("op command timed out after " + TIMEOUT_SECONDS + " seconds");
      }

      int exit = p.exitValue();

      InputStream stdout = p.getInputStream();
      String out = new String(stdout.readAllBytes(), StandardCharsets.UTF_8).trim();

      if (exit != 0) {
        InputStream stderr = p.getErrorStream();
        String err = new String(stderr.readAllBytes(), StandardCharsets.UTF_8).trim();
        String msg = "op read failed with exit code " + exit + (err.isEmpty() ? "" : (": " + err));
        throw new RuntimeException(msg);
      }

      return out;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("op read interrupted", e);
    } catch (Exception e) {
      throw new RuntimeException("failed to read secret via op: " + e.getMessage(), e);
    }
  }

  @Override
  public List<String> getArgumentNames() {
    // Allow a single positional argument (mapped to "ref")
    return Collections.singletonList("ref");
  }
}

