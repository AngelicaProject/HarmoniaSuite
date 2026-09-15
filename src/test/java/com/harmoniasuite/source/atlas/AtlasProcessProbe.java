package com.harmoniasuite.source.atlas;

/** Small Java child used to exercise process capture and termination without a real Atlas binary. */
public final class AtlasProcessProbe {

    private AtlasProcessProbe() {
    }

    public static void main(String[] args) throws InterruptedException {
        switch (args[0]) {
            case "streams" -> {
                System.out.print("atlas stdout");
                System.err.print("atlas stderr");
            }
            case "stdout" -> System.out.print("x".repeat(Integer.parseInt(args[1])));
            case "stderr" -> System.err.print("x".repeat(Integer.parseInt(args[1])));
            case "sleep" -> Thread.sleep(Long.parseLong(args[1]));
            default -> throw new IllegalArgumentException("unknown probe mode");
        }
    }
}
