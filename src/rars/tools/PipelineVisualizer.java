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

import rars.Globals;
import rars.ProgramStatement;
import rars.RISCVprogram;
import rars.riscv.Instruction;
import rars.riscv.hardware.AccessNotice;
import rars.riscv.hardware.AddressErrorException;
import rars.riscv.hardware.Memory;
import rars.riscv.hardware.MemoryAccessNotice;
import rars.riscv.hardware.RegisterFile;
import rars.riscv.instructions.Branch;
import rars.riscv.instructions.JAL;
import rars.riscv.instructions.JALR;
import rars.simulator.BackStepper;
import rars.simulator.Simulator;
import rars.simulator.SimulatorNotice;

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
    // TODO: backstep, maybe?
    // TODO: user statistics (telemetry) - each time pipeline init
    // TODO: line numbers instead of addresses
    // TODO: max speedup
    // TODO: load word, store word prediction wrong? selfmod.s

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
    private JLabel speedup;
    private JButton stepback;
    private int lastAddress = -1; // TODO: is this really needed?

    protected int executedInstructions = 0;
    protected int cyclesTaken = 0;

    // private ArrayList<ProgramStatement> currentPipeline = new ArrayList<>(STAGES); // TODO: remove/replace
    private ProgramStatement[] currentPipeline = new ProgramStatement[STAGES];

    // row and column mappings for cell coloring
    private ArrayList<Map<Integer, Color>> colors = new ArrayList<>(STAGES);

    // stack for backstepping TODO: update pipeline
    private Stack<Integer> backstepStack = new Stack<>();

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

        // scroll to bottom
        pipeline.addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent e) {
                int lastIndex =pipeline.getCellRect(pipeline.getRowCount()-1, 0, false).y;
                pipeline.changeSelection(lastIndex, 0,false,false);
            }
        });

        JScrollPane scrollPane = new JScrollPane(pipeline);
        panel.add(scrollPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout());

        // TODO: make this fancy?
        speedup = new JLabel("Speedup: 0.0");
        bottomPanel.add(speedup, BorderLayout.WEST);

        stepback = new JButton("Step Back");
        stepback.addActionListener(e -> {
            // TODO: make this update venusGUI as well
            BackStepper backStepper = Globals.program.getBackStepper();
            if (backStepper != null && backStepper.enabled() && !backStepper.empty()) {
                backStepper.backStep();
                model.setRowCount(model.getRowCount() - backstepStack.pop());
            }
        });

        bottomPanel.add(stepback, BorderLayout.EAST);
        panel.add(bottomPanel, BorderLayout.SOUTH);

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
        // Simulator.getInstance().addObserver(this);
    }

    // @Override
    // public void update(Observable resource, Object accessNotice) {
    //     if (accessNotice instanceof AccessNotice) {
    //         super.update(resource, accessNotice);
    //     } else if (accessNotice instanceof SimulatorNotice) {
    //         processSimulatorUpdate(resource, (SimulatorNotice) accessNotice);
    //     }
    // }

    // protected void processSimulatorUpdate(Observable resource, SimulatorNotice notice) {
    //     System.out.println(notice.toString());
    // }

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
        int taken = 0;
        int failsafe = STAGES * 3;
        while (taken < failsafe) {
            ret = advancePipeline(stmt);
            updateTable();
            cyclesTaken++;

            // System.out.println("got " + statementToString(ret) + " from " + statementToString(stmt));

            if (stmt.equals(ret)) {
                break;
            }

            if (ret != null) {
                taken = failsafe;
                System.err.println("unexpected return value: " + statementToString(stmt));
                System.err.println("expected return value: " + statementToString(ret));
                System.err.println("pipeline: [" + statementToString(currentPipeline[0]) + ", " + statementToString(currentPipeline[1]) + ", " + statementToString(currentPipeline[2]) + ", " + statementToString(currentPipeline[3]) + ", " + statementToString(currentPipeline[4]) + "]");
                break;
            }

            taken++;
        }

        executedInstructions++;

        if (taken == failsafe) {
            JOptionPane.showMessageDialog(panel, "VAPOR: could not predict pipeline", "VAPOR", JOptionPane.ERROR_MESSAGE);
            reset();
            connectButton.doClick();
            // TODO: telemetry
            System.err.println("VAPOR: could not predict pipeline");
        }

        // update speedup
        // assume 1 cycle per stage with pipeline, 5 cycles per stage without (i e all stalls)
        speedup.setText(String.format("Speedup: %.2f", (double) STAGES * executedInstructions / cyclesTaken));

        // provide info for backstep
        backstepStack.push(taken);
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
        if (isBranchInstruction(wb)) {
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

            // try to predict without using PC (because that's broken) TODO: clean this mess up
            if (isBranchInstruction(currentPipeline[STAGE.EX])) {
                ProgramStatement prediction = predictBranch(currentPipeline[STAGE.EX]);
                if (prediction != null) {
                    nextInMem = prediction;
                }
            }

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

    private static boolean isBranchInstruction(ProgramStatement stmt) {
        if (stmt == null) {
            return false;
        }

        return stmt.getInstruction() instanceof Branch || stmt.getInstruction() instanceof JAL || stmt.getInstruction() instanceof JALR;
    }

    private static ProgramStatement predictBranch(ProgramStatement stmt) {
        if (stmt == null) {
            return null;
        }

        // TODO: clean up

        if (stmt.getInstruction() instanceof Branch) {
            Branch b = (Branch) stmt.getInstruction();
            if (b.willBranch(stmt)) {
                // branch taken
                // System.out.println(Arrays.toString(stmt.getOperands()));
                int offset = stmt.getOperands()[2]; // TODO: is this generic?
                try {
                    return Memory.getInstance().getStatementNoNotify(stmt.getAddress() + offset);
                } catch (AddressErrorException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }

        if (stmt.getInstruction() instanceof JAL) {
            JAL b = (JAL) stmt.getInstruction();
            int offset = stmt.getOperands()[2];
            // System.out.println(Arrays.toString(stmt.getOperands()));
            // System.out.printf("JAL %x\n", offset);
            try {
                return Memory.getInstance().getStatementNoNotify(stmt.getAddress() + offset);
            } catch (AddressErrorException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        if (stmt.getInstruction() instanceof JALR) {
            JALR b = (JALR) stmt.getInstruction();
            int newaddr = RegisterFile.getValue(stmt.getOperand(1)) + stmt.getOperand(2);
            try {
                return Memory.getInstance().getStatementNoNotify(newaddr);
            } catch (AddressErrorException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        return null;
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

        if (isBranchInstruction(id)) return true;
        if (isBranchInstruction(of)) return true;
        if (isBranchInstruction(ex)) return true;

        return false;
    }

    // TODO: merge with top one
    private int hasControlHazardInt() {
        // branch instruction at ID, OF or EX

        ProgramStatement id = currentPipeline[STAGE.ID];
        ProgramStatement of = currentPipeline[STAGE.OF];
        ProgramStatement ex = currentPipeline[STAGE.EX];

        if (isBranchInstruction(id)) return STAGE.ID;
        if (isBranchInstruction(of)) return STAGE.OF;
        if (isBranchInstruction(ex)) return STAGE.EX;

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
        if (isBranchInstruction(currentPipeline[STAGE.WB])) {
            colors.get(STAGE.IF).put(row, Color.LIGHT_GRAY);
        }
    }
}
