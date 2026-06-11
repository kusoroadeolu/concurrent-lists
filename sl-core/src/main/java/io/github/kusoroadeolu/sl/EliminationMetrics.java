package io.github.kusoroadeolu.sl;

public class EliminationMetrics {
    private int nodeSuccesses;
    private int arenaSuccesses;

    public void incNodeSuccesses() {
        this.nodeSuccesses++;
    }

    public void incArenaSuccesses() {
        this.arenaSuccesses++;
    }

    public int nodeSuccesses() {
        return nodeSuccesses;
    }

    public int arenaSuccesses() {
        return arenaSuccesses;
    }

    public void reset() {
        nodeSuccesses = 0;
        arenaSuccesses = 0;
    }
}
