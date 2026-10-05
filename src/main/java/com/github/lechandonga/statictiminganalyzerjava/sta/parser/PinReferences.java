package com.github.lechandonga.statictiminganalyzerjava.sta.parser;

import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.PinRef;

/** Parses a canonical pin/port reference: {@code instance/pin} or a bare port name. */
final class PinReferences {

    private PinReferences() {
    }

    static PinRef parse(String fileName, LineReader.Line line, String token) {
        int slash = token.indexOf('/');
        if (slash <= 0 || slash == token.length() - 1) {
            if (token.indexOf('/') >= 0) {
                throw LineReader.fail(fileName, line,
                        "illegal pin reference '" + token
                                + "' (expected '<instance>/<pin>')");
            }
            return PinRef.port(token);
        }
        if (token.indexOf('/', slash + 1) >= 0) {
            throw LineReader.fail(fileName, line,
                    "illegal pin reference '" + token + "' (only one '/' allowed)");
        }
        return PinRef.pin(token.substring(0, slash), token.substring(slash + 1));
    }
}
