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
import rars.riscv.BasicInstruction;
import rars.riscv.Instruction;
import rars.riscv.hardware.AccessNotice;
import rars.riscv.hardware.AddressErrorException;
import rars.riscv.hardware.Memory;
import rars.riscv.hardware.MemoryAccessNotice;
import rars.riscv.hardware.RegisterFile;
import rars.riscv.instructions.AUIPC;
import rars.riscv.instructions.Arithmetic;
import rars.riscv.instructions.Branch;
import rars.riscv.instructions.ECALL;
import rars.riscv.instructions.Floating;
import rars.riscv.instructions.FusedDouble;
import rars.riscv.instructions.FusedFloat;
import rars.riscv.instructions.ImmediateInstruction;
import rars.riscv.instructions.JAL;
import rars.riscv.instructions.JALR;
import rars.riscv.instructions.LUI;
import rars.riscv.instructions.Load;
import rars.riscv.instructions.SLLI;
import rars.riscv.instructions.SRAI;
import rars.riscv.instructions.SRLI;
import rars.riscv.instructions.Store;
import rars.simulator.BackStepper;
import rars.simulator.Simulator;
import rars.simulator.SimulatorNotice;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.time.Instant;
import java.util.*;

// TODO: javadoc

public class PipelineVisualizer extends AbstractToolAndApplication {
    // TODO: REFACTOR!!!

    // TODO: help button
    // TODO: standalone application
    // TODO: other pipeline types
    // TODO: user statistics (telemetry) - each time pipeline init

    // FETCH, DECODE, OPERAND FETCH, EXECUTE, WRITE BACK
    private static final int STAGES = 5; // TODO: utilize this generally?

    // readable enum
    private static class STAGE {
        public static final int IF = 0;
        public static final int IDOF = 1;
        public static final int EX = 2;
        public static final int MEM = 3;
        public static final int WB = 4;
    }

    private static final int CONTROL_HAZARD_DETECT = STAGE.IDOF;
    private static final int DATA_HAZARD_DETECT = STAGE.IDOF;
    private static final int CONTROL_HAZARD_RESOLVE = STAGE.EX;
    private static final int DATA_HAZARD_RESOLVE = STAGE.WB;

    private static final String CONTROL_HAZARD_LABEL = " \u2BAB"; // arrow
    private static final String DATA_HAZARD_LABEL = " \u26A0\uFE0F"; // warning sign

    private static final String NAME = "VAPOR";
    private static final String VERSION = "1.0 (Johannes Dittrich)";
    private static final String HEADING = "Visualizer for advanced pipelining on RARS";

    private JPanel panel;
    private JTable pipeline;
    private DefaultTableModel model;
    private JLabel speedup;

    // protected int executedInstructions = 0;
    // protected int cyclesTaken = 0;

    private ProgramStatement[] currentPipeline = new ProgramStatement[STAGES];

    // row and column mappings for cell coloring
    private ArrayList<Map<Integer, Color>> colors = new ArrayList<>(STAGES+1);

    // stack for backstepping
    private Stack<Integer> backstepStack = new Stack<>();
    private Stack<ProgramStatement[]> backstepPipelineStack = new Stack<>();

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
        for (int i = 0; i < STAGES+1; i++) {
            colors.add(new HashMap<>());
        }

        panel = new JPanel(new BorderLayout());

        model = new DefaultTableModel();
        model.addColumn("CYCLE");
        model.addColumn("IF");
        model.addColumn("ID/OF");
        model.addColumn("EX");
        model.addColumn("MEM");
        model.addColumn("WB");

        // i have no idea what half of these options do
        pipeline = new JTable(model);
        pipeline.setShowGrid(true);
        pipeline.setGridColor(Color.BLACK);
        pipeline.setRowHeight(20);
        pipeline.setRowSelectionAllowed(false);
        pipeline.setCellSelectionEnabled(true);
        pipeline.setFillsViewportHeight(true);
        pipeline.setDefaultEditor(Object.class, null);

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
        speedup = new JLabel();
        updateSpeedupText();
        bottomPanel.add(speedup, BorderLayout.WEST);
        panel.add(bottomPanel, BorderLayout.SOUTH);

        return panel;
    }

    @Override
    protected JComponent getHelpComponent() {
        final String helpContent = // TODO: make this look better
            "This tool visualizes the pipeline of the RISC-V processor.\n" +
            "- currently supports the basic 5 stage pipeline\n" +
            "- the mnemonics in the pipeline are the assembled non-pseudo instructions\n" +
            "- numbers correspond to the line numbers in the source code\n" +
            "- supports backstep\n" +
            "- supports speedup calculation\n" +
            "- supports branch simulation\n" +
            "- colors and labels cells according to the following scheme:\n" +
            "  - yellow + " + CONTROL_HAZARD_LABEL + ": control hazard\n" +
            "  - cyan + " + DATA_HAZARD_LABEL + ": data hazard\n" +
            "  - green + both labels: both control and data hazard\n" +
            "- known bugs:\n" +
            "  - backstepping does not work over branches\n" +
            "  - self-modifying code breaks pipeline simulation\n";
        JButton help = new JButton("Help");
        help.addActionListener(e -> {
            JOptionPane.showMessageDialog(theWindow, helpContent);
        });
        return help;
    }

    @Override
    protected void reset() {
        model.setRowCount(0);
        // lastAddress = -1;
        for (int i = 0; i < STAGES; i++) {
            currentPipeline[i] = null;
        }
        for (var map : colors) {
            map.clear();
        }
        backstepStack.clear();
        backstepPipelineStack.clear();
        updateSpeedupText();
    }

    @Override
    protected void addAsObserver() {
        addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress);
        // Simulator.getInstance().addObserver(this);
    }

    @Override
    public void update(Observable resource, Object accessNotice) {
        if (accessNotice instanceof AccessNotice) {
            super.update(resource, accessNotice);
        }
        // else if (accessNotice instanceof SimulatorNotice) {
        //     processSimulatorUpdate(resource, (SimulatorNotice) accessNotice);
        // }
        else if (accessNotice instanceof BackStepper) {
            processBackStep();
        }
    }

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
        // TODO: even needed? seems like no
        // int a = memNotice.getAddress();
        // if (a == lastAddress)
        //     return;
        // lastAddress = a;

        ProgramStatement stmt = null;
        try {
            stmt = Memory.getInstance().getStatementNoNotify(memNotice.getAddress());
        } catch (AddressErrorException ignored) {
            // silently ignore
        }

        // very first/last instruction
        if (stmt == null) return;

        // start filling pipeline
        ProgramStatement ret = null;
        int taken = 1;
        backstepPipelineStack.push(currentPipeline.clone());
        int failsafe = STAGES * 3;
        while (taken < failsafe) {
            ret = advancePipeline(stmt);
            updateTable();
            // cyclesTaken++;

            // check if we're done
            if (stmt.equals(ret)) {
                break;
            }

            // simulated wrong execution
            if (ret != null) {
                taken = failsafe;
                System.err.println("unexpected return value: " + statementToString(stmt));
                System.err.println("expected return value: " + statementToString(ret));
                System.err.println("pipeline: [" + statementToString(currentPipeline[0]) + ", " + statementToString(currentPipeline[1]) + ", " + statementToString(currentPipeline[2]) + ", " + statementToString(currentPipeline[3]) + ", " + statementToString(currentPipeline[4]) + "]");
                break;
            }

            taken++;
        }

        // executedInstructions++;

        if (taken == failsafe) {
            JOptionPane.showMessageDialog(panel, "VAPOR: could not predict pipeline", "VAPOR", JOptionPane.ERROR_MESSAGE);
            reset();
            connectButton.doClick();
            // TODO: telemetry
            System.err.println("VAPOR: could not predict pipeline");
        }

        // provide info for backstep
        backstepStack.push(taken);

        // update speedup
        updateSpeedupText();
    }

    private ProgramStatement advancePipeline(ProgramStatement executing) {
        // TODO: control hazards, maybe use PC?

        // pipeline is empty
        if (Arrays.stream(currentPipeline).allMatch(x -> x == null)) {
            // TODO: send telemetry
            // we have to add our observer here as to make a seemless experience for the user
            // yes, this is a hack
            Globals.program.getBackStepper().addObserver(this);
            currentPipeline[STAGE.IF] = executing;
            return null;
        }

        // try to advance pipeline
        // TODO: change to generic for-loop

        ProgramStatement next = nextInstruction();
        int controlHazard = hasControlHazard();
        Set<Integer> dataHazard = hasDataHazard();

        // MEM -> WB
        currentPipeline[STAGE.WB] = currentPipeline[STAGE.MEM];

        // EX -> MEM
        currentPipeline[STAGE.MEM] = currentPipeline[STAGE.EX];

        // IDOF -> EX
        // data hazard
        if (dataHazard.contains(STAGE.IDOF)) { // stall
            currentPipeline[STAGE.EX] = null;
            return currentPipeline[STAGE.WB];
        }
        currentPipeline[STAGE.EX] = currentPipeline[STAGE.IDOF];

        // IF -> IDOF
        // control hazard
        if (controlHazard != -1) { // stall
            currentPipeline[STAGE.IDOF] = null;

            // resolving?
            if (controlHazard == CONTROL_HAZARD_RESOLVE) {
                // update next instruction
                currentPipeline[STAGE.IF] = next;
            }

            return currentPipeline[STAGE.WB];
        }
        currentPipeline[STAGE.IDOF] = currentPipeline[STAGE.IF];

        // next IF
        currentPipeline[STAGE.IF] = next;

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
            // JAL b = (JAL) stmt.getInstruction();
            int offset = stmt.getOperands()[1];
            // System.out.println(Arrays.toString(stmt.getOperands()));
            // System.out.printf("JAL %x\n", offset);
            // System.out.println(stmt.getAddress());
            try {
                return Memory.getInstance().getStatementNoNotify(stmt.getAddress() + offset);
            } catch (AddressErrorException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        if (stmt.getInstruction() instanceof JALR) {
            // JALR b = (JALR) stmt.getInstruction();
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

    private ProgramStatement instructionAfter(ProgramStatement current) {
        ProgramStatement nextInMem = null;
        try {
            // TODO: walk over non-instructions?
            nextInMem = Memory.getInstance().getStatementNoNotify(current.getAddress() + Instruction.INSTRUCTION_LENGTH);
        } catch (AddressErrorException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return nextInMem;
    }

    private ProgramStatement nextInstruction() {
        // control hazard currently resolving?
        if (isBranchInstruction(currentPipeline[CONTROL_HAZARD_RESOLVE])) {
            ProgramStatement branchPrediction = predictBranch(currentPipeline[CONTROL_HAZARD_RESOLVE]);
            if (branchPrediction != null) {
                return branchPrediction;
            }

            return instructionAfter(currentPipeline[CONTROL_HAZARD_RESOLVE]);
        }

        // if not, just use next in memory

        ProgramStatement first = currentPipeline[STAGE.IF];
        if (first == null) { // end of program reached
            return null;
        }

        return instructionAfter(first);
    }

    private Set<Integer> hasDataHazard() {
        ProgramStatement reading = currentPipeline[DATA_HAZARD_DETECT];
        if (reading == null) {
            return new HashSet<>();
        }

        int[] readingRegisters = getReadingRegisters(reading);
        Set<Integer> collisions = new HashSet<Integer>();

        for (int i = DATA_HAZARD_DETECT + 1; i <= DATA_HAZARD_RESOLVE; ++i) {
            ProgramStatement writing = currentPipeline[i];
            if (writing == null) {
                continue;
            }

            int[] writingRegisters = getWritingRegisters(writing);

            for (int rd : readingRegisters) {
                for (int wr : writingRegisters) {
                    if (rd == wr && rd != 0) { // zero register is not a real register
                        collisions.add(i);
                        collisions.add(DATA_HAZARD_DETECT);
                    }
                }
            }
        }

        // no memory collisions because of pipeline architecture

        return collisions;
    }

    private int hasControlHazard() {
        // branch instruction at ID, OF or EX

        for (int i = CONTROL_HAZARD_DETECT; i <= CONTROL_HAZARD_RESOLVE; i++) {
            if (isBranchInstruction(currentPipeline[i])) {
                return i;
            }
        }

        return -1;
    }

    // TODO: refactor this out

    private int[] getReadingRegisters(ProgramStatement stmt) {
        if (stmt == null) {
            return new int[] {};
        }

        Instruction inst = stmt.getInstruction();
        int[] operands = stmt.getOperands();

        if (inst instanceof Arithmetic) {
            return new int[] { operands[1], operands[2] };
        }
        if (inst instanceof Branch) {
            return new int[] { operands[0], operands[1] };
        }
        if (inst instanceof rars.riscv.instructions.Double) {
            return new int[] { operands[1], operands[2] };
        }
        if (inst instanceof Floating) {
            return new int[] { operands[1], operands[2] };
        }
        if (inst instanceof FusedDouble) {
            return new int[] { operands[1], operands[2], operands[3] };
        }
        if (inst instanceof FusedFloat) {
            return new int[] { operands[1], operands[2], operands[3] };
        }
        if (inst instanceof ImmediateInstruction) {
            return new int[] { operands[1] };
        }
        if (inst instanceof Load) {
            return new int[] { operands[2] };
        }
        if (inst instanceof Store) {
            return new int[] { operands[0], operands[2] };
        }
        if (inst instanceof JAL) {
            return new int[] { };
        }
        if (inst instanceof JALR) {
            return new int[] { operands[1] };
        }
        if (inst instanceof AUIPC) {
            return new int[] { };
        }
        if (inst instanceof LUI) {
            return new int[] { };
        }
        if (inst instanceof SRLI) {
            return new int[] { operands[1] };
        }
        if (inst instanceof SRAI) {
            return new int[] { operands[1] };
        }
        if (inst instanceof SLLI) {
            return new int[] { operands[1] };
        }

        // TODO: what to do with ecalls?
        if (inst instanceof ECALL) {
            return new int[] { };
        }

        System.err.println("Unknown instruction type: " + inst.getName());
        return null;
    }

    private int[] getWritingRegisters(ProgramStatement stmt) {
        if (stmt == null) {
            return new int[] {};
        }

        Instruction inst = stmt.getInstruction();
        int[] operands = stmt.getOperands();

        if (inst instanceof Arithmetic) {
            return new int[] { operands[0] };
        }
        if (inst instanceof Branch) {
            return new int[] { };
        }
        if (inst instanceof rars.riscv.instructions.Double) {
            return new int[] { operands[0] };
        }
        if (inst instanceof Floating) {
            return new int[] { operands[0] };
        }
        if (inst instanceof FusedDouble) {
            return new int[] { operands[0] };
        }
        if (inst instanceof FusedFloat) {
            return new int[] { operands[0] };
        }
        if (inst instanceof ImmediateInstruction) {
            return new int[] { operands[0] };
        }
        if (inst instanceof Load) {
            return new int[] { operands[0] };
        }
        if (inst instanceof Store) {
            return new int[] { };
        }
        if (inst instanceof JAL) {
            return new int[] { operands[0] };
        }
        if (inst instanceof JALR) {
            return new int[] { operands[0] };
        }
        if (inst instanceof AUIPC) {
            return new int[] { operands[0] };
        }
        if (inst instanceof LUI) {
            return new int[] { operands[0] };
        }
        if (inst instanceof SRLI) {
            return new int[] { operands[0] };
        }
        if (inst instanceof SRAI) {
            return new int[] { operands[0] };
        }
        if (inst instanceof SLLI) {
            return new int[] { operands[0] };
        }


        // TODO: what to do with ecalls?
        if (inst instanceof ECALL) {
            return new int[] { };
        }

        System.err.println("Unknown instruction type: " + inst.getName());
        return null;
    }

    private void processBackStep() {
        if (backstepStack.empty()) {
            return;
        }

        int count = backstepStack.pop();

        // delete rows and color
        for (int i = 0; i < count; i++) {
            int rows = model.getRowCount();

            model.removeRow(rows-1);
            colors.forEach((map) -> {
                map.remove(rows-1);
            });
        }

        currentPipeline = backstepPipelineStack.pop();
        updateSpeedupText();
    }

    private String statementToString(ProgramStatement stmt) {
        if (stmt == null) return "";
        return stmt.getInstruction().getName() + " " + stmt.getSourceLine();
    }

    private void updateTable() {
        int rows = model.getRowCount();
        int controlHazard = hasControlHazard();
        Set<Integer> dataHazards = hasDataHazard();

        Object[] newrow = new Object[STAGES+1];
        newrow[0] = rows+1;
        for (int i = 0; i < STAGES; i++) {
            StringBuilder sb = new StringBuilder();
            sb.append(statementToString(currentPipeline[i]));
            if (dataHazards.contains(i)) {
                sb.append(DATA_HAZARD_LABEL);
            }
            if (controlHazard == i) {
                sb.append(CONTROL_HAZARD_LABEL);
            }
            newrow[i+1] = sb.toString();
        }

        // write pipeline to table
        model.addRow(newrow);

        // add color

        // color for cycle column
        colors.get(0).put(rows, Color.LIGHT_GRAY);

        // control hazard
        if (controlHazard != -1) {
            colors.get(controlHazard+1).put(rows, Color.YELLOW);
        }

        // data hazard
        for (int stage : hasDataHazard()) {
            stage++; // +1 because of cycle column

            // already has control hazard
            if (colors.get(stage).containsKey(rows)) {
                colors.get(stage).put(rows, Color.GREEN);
            } else {
                colors.get(stage).put(rows, Color.CYAN);
            }
        }

        // still uncertain fetch (from control hazard - limitation of simulation)
        // if (isBranchInstruction(currentPipeline[STAGE.WB])) {
        //     colors.get(STAGE.IF).put(row, Color.LIGHT_GRAY);
        // }
    }

    private void updateSpeedupText() {
        // assume 1 cycle per stage with pipeline, 5 cycles per stage without (i e all stalls)

        int instructionsExecuted = backstepStack.size();
        int totalCyclesTaken = model.getRowCount();

        StringBuilder sb = new StringBuilder();
        sb.append("<html>");

        sb.append("Speedup: ");
        sb.append(String.format("%.2f", (double) STAGES * instructionsExecuted / totalCyclesTaken));
        sb.append("<br/>");

        sb.append("Maximum theoretical speedup: ");
        sb.append(String.format("%.2f", (double) STAGES * instructionsExecuted / (STAGES + instructionsExecuted - 1)));

        sb.append("</html>");

        speedup.setText(sb.toString());
    }
}
