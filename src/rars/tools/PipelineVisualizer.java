/*
Copyright (c) 2023, Johannes Dittrich, Friedrich Alexander University Erlangen, Germany

Developed by Johannes Dittrich (johannes.dittrich(@)fau.de)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */
package rars.tools;

// TODO: optimize imports

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;

import rars.ProgramStatement;
import rars.riscv.Instruction;
import rars.riscv.hardware.AccessNotice;
import rars.riscv.hardware.AddressErrorException;
import rars.riscv.hardware.Memory;
import rars.riscv.hardware.MemoryAccessNotice;
import rars.riscv.instructions.Branch;

import java.awt.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Observable;

// TODO: javadoc

public class PipelineVisualizer extends AbstractToolAndApplication {

    // TODO: standalone application
    // TODO: other pipeline types
    // TODO: backstep
    // TODO: user statistics (telemetry)
    // TODO: speedup calculator
    // TODO: line numbers instead of addresses

    // FETCH, DECODE, OPERAND FETCH, EXECUTE, WRITE BACK
    private static final int STAGES = 5; // TODO: utilize this generally?

    // readable enum
    private static class STAGE {
        public static final int IF = 0;
        public static final int ID = 1;
        public static final int OF = 2;
        public static final int EX = 3;
        public static final int WB = 4;
    }

    private static final String NAME = "VAPOR";
    private static final String VERSION = "1.0 (Johannes Dittrich)";
    private static final String HEADING = "Visualizer for advanced pipelining on RARS";

    private JTable pipeline;
    private DefaultTableModel model;
    private int lastAddress = -1; // TODO: is this really needed?

    // private ArrayList<ProgramStatement> currentPipeline = new ArrayList<>(STAGES); // TODO: remove/replace
    private ProgramStatement[] currentPipeline = new ProgramStatement[STAGES];

    public PipelineVisualizer(String title, String heading) {
        super(title, heading);
    }

    public PipelineVisualizer() {
        super(NAME + ", " + VERSION, HEADING);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    protected JComponent buildMainDisplayArea() {
        JPanel panel = new JPanel(new BorderLayout());

        model = new DefaultTableModel();
        model.addColumn("IF");
        model.addColumn("ID");
        model.addColumn("OF");
        model.addColumn("EX");
        model.addColumn("WB");

        // i have no idea what half of these options do
        pipeline = new JTable(model);
        pipeline.setShowGrid(true);
        pipeline.setGridColor(Color.BLACK);
        pipeline.setRowHeight(20);
        pipeline.setRowSelectionAllowed(false);
        pipeline.setCellSelectionEnabled(true);
        pipeline.setFillsViewportHeight(true);

        JScrollPane scrollPane = new JScrollPane(pipeline);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    @Override
    protected void reset() {
        model.setRowCount(0);
        lastAddress = -1;
        // currentPipeline.clear();
        // currentPipeline.ensureCapacity(STAGES);
    }

    @Override
    protected void addAsObserver() {
        addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress);
    }

    @Override
    protected void processRISCVUpdate(Observable resource, AccessNotice notice) {
        // only process if the access is from RISCV
        if (!notice.accessIsFromRISCV()) return;

        // only process if the access is a read
        if (notice.getAccessType() != AccessNotice.READ) return;

        // only process if the access is a memory access
        if (!(notice instanceof MemoryAccessNotice)) return;

        MemoryAccessNotice memNotice = (MemoryAccessNotice) notice;

        // stolen from Felipe Lessa's instruction counter
        int a = memNotice.getAddress();
        if (a == lastAddress)
            return;
        lastAddress = a;

        ProgramStatement stmt = null;
        try {
            stmt = Memory.getInstance().getStatementNoNotify(memNotice.getAddress());
        } catch (AddressErrorException ignored) {
            // silently ignore
        }

        // very last instruction
        if (stmt == null) return;

        // System.out.println(Arrays.toString(stmt.getOperands()));

        // String instructionName = stmt.getInstruction().getName();

        // insert into table
        // model.addRow(new Object[] {instructionName, "", "", "", ""});

        ProgramStatement ret = null;
        int failsafe = STAGES * 2;
        while (!stmt.equals(ret) && failsafe > 0) {
            // TODO: weird bug where branch as stmt will only show in table after next instruction
            ret = advancePipeline(stmt);
            updateTable();
            failsafe--;
        }

        if (failsafe == 0) {
            // TODO: infobox
            System.err.println("VAPOR: could not predict pipeline");
        }
    }

    private ProgramStatement advancePipeline(ProgramStatement next) {
        // TODO: control hazards

        // pipeline is empty
        // if (currentPipeline.stream().allMatch(x -> x == null)) {
        if (Arrays.stream(currentPipeline).allMatch(x -> x == null)) {
            // currentPipeline.set(STAGE.IF, next);
            currentPipeline[STAGE.IF] = next;
            return null;
        }

        // try to advance pipeline

        // ProgramStatement first = currentPipeline.stream().filter(x -> x != null).findFirst().orElse(null);

        // next fetched instruction
        ProgramStatement nextInMem = null;
        if (!hasControlHazard()) {
            // ProgramStatement first = currentPipeline.get(STAGE.IF);
            ProgramStatement first = currentPipeline[STAGE.IF];

            if (first != null) {
                try {
                    // TODO: walk over non-instructions
                    nextInMem = Memory.getInstance().getStatementNoNotify(first.getAddress() + 4);
                } catch (AddressErrorException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            } else {
                nextInMem = next;
            }
        } else {
            // TODO: during control hazard, instruction is stalled in IF. only gets replaced after branch leaves EX

            // flush pipeline
            // currentPipeline.set(STAGE.IF, null);
            currentPipeline[STAGE.IF] = null;
            // currentPipeline.set(STAGE.ID, null);
        }


        // commit last instruction
        // ProgramStatement commit = currentPipeline.get(STAGE.WB);
        ProgramStatement commit = currentPipeline[STAGE.WB];

        // advance pipeline
        // currentPipeline.set(STAGE.WB, currentPipeline.get(STAGE.EX));
        currentPipeline[STAGE.WB] = currentPipeline[STAGE.EX];
        // currentPipeline.set(STAGE.EX, currentPipeline.get(STAGE.OF));
        currentPipeline[STAGE.EX] = currentPipeline[STAGE.OF];

        // data hazards
        // if (hasDataHazard(currentPipeline.get(STAGE.ID))) {
        //     currentPipeline.set(STAGE.OF, null);
        // } else {
        //     currentPipeline.set(STAGE.OF, currentPipeline.get(STAGE.ID));
        //     currentPipeline.set(STAGE.ID, currentPipeline.get(STAGE.IF));
        //     currentPipeline.set(STAGE.IF, nextInMem);
        // }
        if (hasDataHazard(currentPipeline[STAGE.ID])) {
            // TODO: is this right?
            currentPipeline[STAGE.OF] = null;
        } else {
            currentPipeline[STAGE.OF] = currentPipeline[STAGE.ID];
            currentPipeline[STAGE.ID] = currentPipeline[STAGE.IF];
            currentPipeline[STAGE.IF] = nextInMem;
        }

        return commit;
    }

    private boolean hasDataHazard(ProgramStatement reading) {
        // hazard in EX or WB

        // reading.getInstruction()

        // int[] ops = reading.getOperands();
        // int[] ops2 = writing.getOperands();

        return false;
    }

    private boolean hasControlHazard() {
        // branch instruction at ID, OF or EX

        // ProgramStatement id = currentPipeline.get(STAGE.ID);
        // ProgramStatement of = currentPipeline.get(STAGE.OF);
        // ProgramStatement ex = currentPipeline.get(STAGE.EX);
        ProgramStatement id = currentPipeline[STAGE.ID];
        ProgramStatement of = currentPipeline[STAGE.OF];
        ProgramStatement ex = currentPipeline[STAGE.EX];

        if (id != null && id.getInstruction() instanceof Branch) return true;
        if (of != null && of.getInstruction() instanceof Branch) return true;
        if (ex != null && ex.getInstruction() instanceof Branch) return true;

        return false;
    }

    private String statementToString(ProgramStatement stmt) {
        if (stmt == null) return "";
        return stmt.getInstruction().getName() + " " + stmt.getAddress();
    }

    private void updateTable() {
        // TODO: coloring for hazards

        // write pipeline to table
        model.addRow(new Object[] {
            // statementToString(currentPipeline.get(STAGE.IF)),
            // statementToString(currentPipeline.get(STAGE.ID)),
            // statementToString(currentPipeline.get(STAGE.OF)),
            // statementToString(currentPipeline.get(STAGE.EX)),
            // statementToString(currentPipeline.get(STAGE.WB))
            statementToString(currentPipeline[STAGE.IF]),
            statementToString(currentPipeline[STAGE.ID]),
            statementToString(currentPipeline[STAGE.OF]),
            statementToString(currentPipeline[STAGE.EX]),
            statementToString(currentPipeline[STAGE.WB])
        });
    }
}
