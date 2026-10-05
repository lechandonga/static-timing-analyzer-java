package com.github.lechandonga.statictiminganalyzerjava.sta.service;

import com.github.lechandonga.statictiminganalyzerjava.sta.analysis.TimingAnalyzer;
import com.github.lechandonga.statictiminganalyzerjava.sta.analysis.TimingReport;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.CellLibrary;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Constraints;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Netlist;
import com.github.lechandonga.statictiminganalyzerjava.sta.parse.CellLibraryParser;
import com.github.lechandonga.statictiminganalyzerjava.sta.parse.ConstraintsParser;
import com.github.lechandonga.statictiminganalyzerjava.sta.parse.NetlistParser;

import java.nio.file.Path;

/** 门面服务：解析三份本地文件并执行静态时序分析。纯函数式，可安全重复调用。 */
public class StaService {

    public TimingReport analyze(Path libraryFile, Path netlistFile, Path constraintsFile) {
        CellLibrary library = new CellLibraryParser().parse(libraryFile);
        Netlist netlist = new NetlistParser().parse(netlistFile, library);
        Constraints constraints = new ConstraintsParser().parse(constraintsFile);
        return new TimingAnalyzer(library, netlist, constraints).analyze();
    }
}
