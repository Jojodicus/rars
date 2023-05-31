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
import javax.swing.table.DefaultTableCellRenderer;
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
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Observable;

// TODO: javadoc

public class PipelineVisualizer extends AbstractToolAndApplication {
    // TODO: help button
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

    private JPanel panel;
    private JTable pipeline;
    private DefaultTableModel model;
    private int lastAddress = -1; // TODO: is this really needed?

    // private ArrayList<ProgramStatement> currentPipeline = new ArrayList<>(STAGES); // TODO: remove/replace
    private ProgramStatement[] currentPipeline = new ProgramStatement[STAGES];

    // row and column mappings for cell coloring
    private ArrayList<Map<Integer, Color>> colors = new ArrayList<>(STAGES);

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
        for (int i = 0; i < STAGES; i++) {
            colors.add(new HashMap<>());
        }

        panel = new JPanel(new BorderLayout());

        model = new DefaultTableModel();
        model.addColumn("IF");
        model.addColumn("ID");
        model.addColumn("OF");
        model.addColumn("EX");
        model.addColumn("WB");

        // i have no idea what half of these options do TODO: find out
        pipeline = new JTable(model);
        pipeline.setShowGrid(true);
        pipeline.setGridColor(Color.BLACK);
        pipeline.setRowHeight(20);
        pipeline.setRowSelectionAllowed(false);
        pipeline.setCellSelectionEnabled(true);
        pipeline.setFillsViewportHeight(true);

        // custom renderer for coloring cells
        pipeline.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

                if (colors.get(column).containsKey(row)) {
                    c.setBackground(colors.get(column).get(row));
                } else {
                    c.setBackground(Color.WHITE);
                }

                return c;
            }
        });


        JScrollPane scrollPane = new JScrollPane(pipeline);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    @Override
    protected void reset() {
        model.setRowCount(0);
        lastAddress = -1;
        for (int i = 0; i < STAGES; i++) {
            currentPipeline[i] = null;
        }
        for (var map : colors) {
            map.clear();
        }
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

        // very first/last instruction
        if (stmt == null) return;

        // System.out.println(Arrays.toString(stmt.getOperands()));

        // String instructionName = stmt.getInstruction().getName();

        // insert into table
        // model.addRow(new Object[] {instructionName, "", "", "", ""});

        ProgramStatement ret = null;
        int failsafe = STAGES * 3;
        while (failsafe > 0) {
            ret = advancePipeline(stmt);
            updateTable();

            // System.out.println("got " + statementToString(ret) + " from " + statementToString(stmt));

            if (stmt.equals(ret)) {
                break;
            }

            if (ret != null) {
                failsafe = 0;
                System.err.println("unexpected return value: " + statementToString(stmt));
                System.err.println("expected return value: " + statementToString(ret));
                System.err.println("pipeline: [" + statementToString(currentPipeline[0]) + ", " + statementToString(currentPipeline[1]) + ", " + statementToString(currentPipeline[2]) + ", " + statementToString(currentPipeline[3]) + ", " + statementToString(currentPipeline[4]) + "]");
                break;
            }

            failsafe--;
        }

        if (failsafe == 0) {
            JOptionPane.showMessageDialog(panel, "VAPOR: could not predict pipeline", "VAPOR", JOptionPane.ERROR_MESSAGE);
            reset();
            connectButton.doClick();
            // TODO: telemetry
            System.err.println("VAPOR: could not predict pipeline");
        }
    }

    private ProgramStatement advancePipeline(ProgramStatement next) {
        // TODO: control hazards, maybe use PC?

        // pipeline is empty
        if (Arrays.stream(currentPipeline).allMatch(x -> x == null)) {
            currentPipeline[STAGE.IF] = next;
            return null;
        }

        // try to advance pipeline

        // control hazard resolved // TODO: optimize check
        ProgramStatement wb = currentPipeline[STAGE.WB];
        if (wb != null && wb.getInstruction() instanceof Branch) {
            // update last prediction
            model.setValueAt(statementToString(next), model.getRowCount()-1, STAGE.IF);
            colors.get(STAGE.IF).remove(model.getRowCount()-1);
            currentPipeline[STAGE.IF] = next;

            // nextInMem = next;
        }

        // next fetched instruction
        ProgramStatement nextInMem = null;
        if (!hasControlHazard()) {
            ProgramStatement first = currentPipeline[STAGE.IF];

            if (first != null) {
                try {
                    // TODO: walk over non-instructions?
                    nextInMem = Memory.getInstance().getStatementNoNotify(first.getAddress() + Instruction.INSTRUCTION_LENGTH);
                } catch (AddressErrorException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            } else {
                // end of program reached
                nextInMem = null;
            }
        } else {
            // flush pipeline
            nextInMem = currentPipeline[STAGE.IF];
            currentPipeline[STAGE.IF] = null;
        }

        // advance pipeline
        currentPipeline[STAGE.WB] = currentPipeline[STAGE.EX];
        currentPipeline[STAGE.EX] = currentPipeline[STAGE.OF];

        // data hazards
        if (hasDataHazard(currentPipeline[STAGE.ID])) {
            // TODO: is this right?
            currentPipeline[STAGE.OF] = null;
        } else {
            currentPipeline[STAGE.OF] = currentPipeline[STAGE.ID];
            currentPipeline[STAGE.ID] = currentPipeline[STAGE.IF];
            currentPipeline[STAGE.IF] = nextInMem;
        }

        return currentPipeline[STAGE.WB];
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

        ProgramStatement id = currentPipeline[STAGE.ID];
        ProgramStatement of = currentPipeline[STAGE.OF];
        ProgramStatement ex = currentPipeline[STAGE.EX];

        if (id != null && id.getInstruction() instanceof Branch) return true;
        if (of != null && of.getInstruction() instanceof Branch) return true;
        if (ex != null && ex.getInstruction() instanceof Branch) return true;

        return false;
    }

    // TODO: merge with top one
    private int hasControlHazardInt() {
        // branch instruction at ID, OF or EX

        ProgramStatement id = currentPipeline[STAGE.ID];
        ProgramStatement of = currentPipeline[STAGE.OF];
        ProgramStatement ex = currentPipeline[STAGE.EX];

        if (id != null && id.getInstruction() instanceof Branch) return STAGE.ID;
        if (of != null && of.getInstruction() instanceof Branch) return STAGE.OF;
        if (ex != null && ex.getInstruction() instanceof Branch) return STAGE.EX;

        return -1;
    }

    private String statementToString(ProgramStatement stmt) {
        if (stmt == null) return "";
        return stmt.getInstruction().getName() + " " + stmt.getSourceLine();
    }

    private void updateTable() {
        // TODO: coloring for hazards

        // write pipeline to table
        model.addRow(new Object[] {
            statementToString(currentPipeline[STAGE.IF]),
            statementToString(currentPipeline[STAGE.ID]),
            statementToString(currentPipeline[STAGE.OF]),
            statementToString(currentPipeline[STAGE.EX]),
            statementToString(currentPipeline[STAGE.WB])
        });

        // add color

        // control hazard
        int row = model.getRowCount()-1;
        int col = hasControlHazardInt();
        if (col != -1) {
            colors.get(col).put(row, Color.YELLOW);
        }

        // still uncertain fetch (from control hazard - limitation of simulation)
        if (currentPipeline[STAGE.WB] != null && currentPipeline[STAGE.WB].getInstruction() instanceof Branch) {
            colors.get(STAGE.IF).put(row, Color.LIGHT_GRAY);
        }

        // scroll to bottom
        pipeline.addComponentListener(new ComponentAdapter() {
        public void componentResized(ComponentEvent e) {
            int lastIndex =pipeline.getCellRect(pipeline.getRowCount()-1, 0, false).y;
            pipeline.changeSelection(lastIndex, 0,false,false);
        }
    });
    }
}
