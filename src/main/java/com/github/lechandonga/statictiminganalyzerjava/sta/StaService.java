package com.github.lechandonga.statictiminganalyzerjava.sta;

import com.github.lechandonga.statictiminganalyzerjava.sta.constraint.Constraints;
import com.github.lechandonga.statictiminganalyzerjava.sta.engine.CombinationalLoopException;
import com.github.lechandonga.statictiminganalyzerjava.sta.engine.TimingEngine;
import com.github.lechandonga.statictiminganalyzerjava.sta.engine.TimingReport;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.Library;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.Netlist;
import com.github.lechandonga.statictiminganalyzerjava.sta.parser.LibraryParser;
import com.github.lechandonga.statictiminganalyzerjava.sta.parser.NetlistParser;
import com.github.lechandonga.statictiminganalyzerjava.sta.parser.SdcParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Entry point for running a complete local static timing analysis from three files. */
public final class StaService {

    private StaService() {
    }

    public static TimingReport analyzeFiles(Path libraryFile, Path netlistFile,
                                            Path constraintFile) {
        String libraryText = readFile(libraryFile);
        String netlistText = readFile(netlistFile);
        String constraintText = readFile(constraintFile);

        Library library = LibraryParser.parse(libraryFile.getFileName().toString(), libraryText);
        Netlist netlist = NetlistParser.parse(netlistFile.getFileName().toString(), netlistText);
        Constraints constraints =
                SdcParser.parse(constraintFile.getFileName().toString(), constraintText);

        return TimingEngine.analyze(netlist, library, constraints);
    }

    public static TimingReport analyzeText(String libraryName, String libraryText,
                                           String netlistName, String netlistText,
                                           String constraintName, String constraintText) {
        Library library = LibraryParser.parse(libraryName, libraryText);
        Netlist netlist = NetlistParser.parse(netlistName, netlistText);
        Constraints constraints = SdcParser.parse(constraintName, constraintText);
        return TimingEngine.analyze(netlist, library, constraints);
    }

    private static String readFile(Path path) {
        if (!Files.exists(path)) {
            throw new StaException(StaException.Code.UNKNOWN_REFERENCE,
                    "input file does not exist: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new StaException(StaException.Code.FORMAT_ERROR,
                    "input path is not a regular file: " + path);
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StaException(StaException.Code.PARSE_ERROR,
                    "cannot read " + path + ": " + e.getMessage(), e);
        }
    }
}
