package com.github.lechandonga.statictiminganalyzerjava.sta.engine;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;

import java.util.List;

/** Raised when one or more combinational loops make the timing result undefined. */
public class CombinationalLoopException extends StaException {

    private final transient List<List<String>> loops;

    public CombinationalLoopException(List<List<String>> loops) {
        super(Code.COMBINATIONAL_LOOP,
                "combinational loop detected involving nodes: " + loops);
        this.loops = loops;
    }

    public List<List<String>> getLoops() {
        return loops;
    }
}
