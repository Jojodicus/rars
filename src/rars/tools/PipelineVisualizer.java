/*
Copyright (c) 2024, Johannes Dittrich, Friedrich Alexander University Erlangen, Germany

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

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.concurrent.*;
import rars.*;
import rars.assembler.*;
import rars.riscv.*;
import rars.riscv.hardware.*;
import rars.riscv.instructions.*;
import rars.simulator.*;


public class PipelineVisualizer extends AbstractToolAndApplication {
    // FETCH, DECODE, OPERAND FETCH, EXECUTE, WRITE BACK
    private static final int STAGES = 5;

    // readable enum
    private static class STAGE {
        public static final int IF = 0;
        public static final int IDOF = IF + 1;
        public static final int EX = IDOF + 1;
        public static final int MEM = EX + 1;
        public static final int WB = MEM + 1;
    }

    private static final int CONTROL_HAZARD_DETECT = STAGE.IDOF;
    private static final int DATA_HAZARD_DETECT = STAGE.IDOF;
    private static final int CONTROL_HAZARD_RESOLVE = STAGE.EX;
    private static final int DATA_HAZARD_RESOLVE = STAGE.WB;

    private static final String CONTROL_HAZARD_LABEL = " \u2BAB"; // arrow
    private static final String DATA_HAZARD_LABEL = " \u26A0\uFE0F"; // warning sign

    private static final String NAME = "Pipeline Visualizer";
    private static final String VERSION = "1.0 (Johannes Dittrich)";
    private static final String HEADING = "VAPOR - Visualizer for advanced pipelining on RARS";

    private PipelineVisualizerGUI gui;

    // protected int executedInstructions = 0;
    // protected int cyclesTaken = 0;

    private ProgramStatement[] currentPipeline = new ProgramStatement[STAGES];
    private RISCVprogram currentProgram = null;

    // which lines should be inserted into the pipeline
    private Set<Integer> measuredLines = new HashSet<>();
    private static final String MEASURE_START = "PIPELINE_MEASURE_START";
    private static final String MEASURE_END = "PIPELINE_MEASURE_END";

    // stack for backstepping
    private Stack<Integer> backstepStack = new Stack<>();
    private Stack<ProgramStatement[]> backstepPipelineStack = new Stack<>();

    // statistics for standalone app
    // WARNING: does not play nice with backstep, but not necessary since it's only used for non-gui standalone app
    private int dataHazardStatistics;
    private int controlHazardStatistics;

    public PipelineVisualizer(String title, String heading) {
        super(title, heading);
    }

    public PipelineVisualizer() {
        super(NAME + ", " + VERSION, HEADING);
    }

    // run stand-alone, with rars as a backend
    // by providing a file (alongside optional arguments for it) as parameters, we can run it instantly
    // when a second asm file is given, the first one will serve as the "reference", with statistics being printed only for the second one
    // but in addition, the first file is also run and compared to the output of the second one, testing for unchanged semantics
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> standalone(args));
    }

    private static void standalone(String[] args) {
        PipelineVisualizer vapor = new PipelineVisualizer();

        vapor.go();

        // run program
        if (args.length > 0) {
            SimulatorNotice firstNotice = vapor.runProgram(args[0]);

            if (args.length > 1) {
                // save status for comparison
                java.util.List<Long> original = Arrays.stream(RegisterFile.getRegisters()).map(x -> x.getValueNoNotify()).toList();

                SimulatorNotice secondNotice = vapor.runProgram(args[1]);
                Register[] modified = RegisterFile.getRegisters();

                // compare
                if (firstNotice.getReason() != secondNotice.getReason()) {
                    System.out.printf("%s: reason differed: %s%n", args[1], secondNotice.getReason().name());
                    System.exit(1);
                }
                for (int i = 0; i < original.size(); i++) {
                    if (original.get(i) != modified[i].getValue()) {
                        System.out.printf("%s: %s differed ~ %d <-> %d%n",
                            args[1], modified[i].getName(), original.get(i), modified[i].getValueNoNotify());
                        System.exit(1);
                    }
                }
            }

            vapor.printStatistics();
            System.exit(0);
        }
    }

    private SimulatorNotice runProgram(String path) {
        ArrayList<String> parameters = new ArrayList<>();

        RISCVprogram program = new RISCVprogram();
        rars.Globals.program = program;
        ArrayList<RISCVprogram> programsToAssemble;
        try {
            ArrayList<String> program_arraylist = new ArrayList<>();
            program_arraylist.add(path);
            programsToAssemble = program.prepareFilesForAssembly(program_arraylist, path, null);
        } catch (AssemblyException pe) {
            return null;
        }
        try {
            program.assemble(programsToAssemble, Globals.getSettings().getBooleanSetting(Settings.Bool.EXTENDED_ASSEMBLER_ENABLED),
                    Globals.getSettings().getBooleanSetting(Settings.Bool.WARNINGS_ARE_ERRORS));
        } catch (AssemblyException pe) {
            return null;
        }
        RegisterFile.resetRegisters();
        FloatingPointRegisterFile.resetRegisters();
        ControlAndStatusRegisterFile.resetRegisters();
        InterruptController.reset();
        addAsObserver();
        // vapor.observing = true; // seems like this doesn't impact results

        Exchanger<SimulatorNotice> finishNoticeExchanger = new Exchanger<>();
        final Observer stopListener =
                new Observer() {
                    public void update(Observable o, Object simulator) {
                        SimulatorNotice notice = ((SimulatorNotice) simulator);
                        if (notice.getAction() != SimulatorNotice.SIMULATOR_STOP) return;
                        deleteAsObserver();
                        // vapor.observing = false;
                        o.deleteObserver(this);

                        try {
                            finishNoticeExchanger.exchange(notice);
                        } catch (InterruptedException ignored) {
                        }
                    }
                };
        Simulator.getInstance().addObserver(stopListener);
        program.startSimulation(-1, null);
        SimulatorNotice ret = null;
        try {
            ret = finishNoticeExchanger.exchange(null);
        } catch (InterruptedException ignored) { }
        return ret;
    }

    public void printStatistics() {
        System.out.println("Cycles taken: " + totalCyclesTaken());
        System.out.println("Instructions executed: " + instructionsExecuted());
        System.out.println("Speedup: " + speedup());
        System.out.println("Speedup without hazards: " + speedupWithoutHazards());
        System.out.println("Data hazards: " + dataHazardStatistics);
        System.out.println("Control hazards: " + controlHazardStatistics);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    protected void initializePreGUI() {
        gui = new PipelineVisualizerGUI(STAGES);
        // make our life a bit easier, can probably be abstracted away
    }

    @Override
    protected JComponent buildMainDisplayArea() {
        return gui.buildMainDisplayArea();
    }

    @Override
    protected JComponent getHelpComponent() {
        return gui.getHelpComponent();
    }

    @Override
    protected void reset() {
        gui.getModel().setRowCount(0);
        // lastAddress = -1;
        for (int i = 0; i < STAGES; i++) {
            currentPipeline[i] = null;
        }
        for (Map<Integer, Color> map : gui.getColors()) {
            map.clear();
        }
        backstepStack.clear();
        backstepPipelineStack.clear();
        measuredLines.clear();
        currentProgram = null;
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
        } else if (accessNotice instanceof BackStepper) {
            processBackStep();
        }
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

        ProgramStatement stmt = null;
        try {
            stmt = Memory.getInstance().getStatementNoNotify(memNotice.getAddress());
        } catch (AddressErrorException ignored) {
            // silently ignore
        }

        // very first/last instruction
        if (stmt == null) {
            return;
        }

        // check if we're in a new program
        if (!Globals.program.equals(currentProgram)) {
            // if so, reset
            reset();
        }

        // per-program initialization
        // yes, this is really hacky
        if (backstepPipelineStack.empty()) {
            // set program
            currentProgram = Globals.program;

            // yes, this probably leaks memory when constantly switching between programs
            currentProgram.getBackStepper().addObserver(this);

            // parse measuring ranges
            ArrayList<SourceLine> src = currentProgram.getSourceLineList();
            boolean inRange = false;
            for (SourceLine line : src) {
                String content = line.getSource();

                // check if we're at the start of a measuring range
                if (content.contains(MEASURE_START)) {
                    inRange = true;
                }

                // add line to measuring range
                if (inRange) {
                    measuredLines.add(line.getLineNumber());
                }

                // check if we're at the end of a measuring range
                if (content.contains(MEASURE_END)) {
                    inRange = false;
                    continue;
                }
            }
        }

        // check if we're in a measuring range when we defined one
        if (!(measuredLines.isEmpty() || measuredLines.contains(stmt.getSourceLine()))) {
            // skip line and prepare for next line

            // clear pipeline
            for (int i = 0; i < STAGES; i++) {
                currentPipeline[i] = null;
            }

            // add color marker to table
            gui.getColors().get(0).put(gui.getModel().getRowCount(), Color.DARK_GRAY);

            return;
        }

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
            JOptionPane.showMessageDialog(gui.getPanel(), "VAPOR: could not predict pipeline", "VAPOR", JOptionPane.ERROR_MESSAGE);
            reset();
            connectButton.doClick();
            System.err.println("VAPOR: could not predict pipeline");
        }

        // provide info for backstep
        backstepStack.push(taken);

        // update speedup
        updateSpeedupText();
    }

    private ProgramStatement advancePipeline(ProgramStatement executing) {
        // pipeline is empty
        if (Arrays.stream(currentPipeline).allMatch(x -> x == null)) {
            currentPipeline[STAGE.IF] = executing;
            return null;
        }

        // try to advance pipeline

        ProgramStatement next = nextInstruction();
        Set<Integer> controlHazard = hasControlHazard();
        Set<Integer> dataHazard = hasDataHazard();

        // statistics
        if (!controlHazard.isEmpty()) {
            controlHazardStatistics++;
        }
        if (!dataHazard.isEmpty()) {
            dataHazardStatistics++;
        }

        // MEM -> WB
        currentPipeline[STAGE.WB] = currentPipeline[STAGE.MEM];

        // EX -> MEM
        currentPipeline[STAGE.MEM] = currentPipeline[STAGE.EX];

        // IDOF -> EX
        // data hazard
        if (dataHazard.contains(STAGE.IDOF)) { // stall
            currentPipeline[STAGE.EX] = null;
            // flush and stall cuz control hazard resolving
            if (controlHazard.contains(CONTROL_HAZARD_RESOLVE)) {
                currentPipeline[STAGE.IDOF] = null;
                currentPipeline[STAGE.IF] = next;
                return currentPipeline[STAGE.WB];
            }
            return currentPipeline[STAGE.WB];
        }
        if (!controlHazard.isEmpty()) { // possible control hazard
            // one last push
            if (controlHazard.size() == 1 && controlHazard.contains(CONTROL_HAZARD_DETECT)) {
                currentPipeline[STAGE.EX] = currentPipeline[STAGE.IDOF];
                currentPipeline[STAGE.IDOF] = currentPipeline[STAGE.IF];
                currentPipeline[STAGE.IF] = next;
                return currentPipeline[STAGE.WB];
            }

            // flush and stall
            if (controlHazard.contains(CONTROL_HAZARD_RESOLVE)) {
                currentPipeline[STAGE.EX] = null;
                currentPipeline[STAGE.IDOF] = null;
                currentPipeline[STAGE.IF] = next;
                return currentPipeline[STAGE.WB];
            }

            // stall
            currentPipeline[STAGE.EX] = null;
            return currentPipeline[STAGE.WB];
        }
        currentPipeline[STAGE.EX] = currentPipeline[STAGE.IDOF];

        // IF -> IDOF
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

        if (stmt.getInstruction() instanceof Branch) {
            Branch b = (Branch) stmt.getInstruction();
            if (b.willBranch(stmt)) {
                // branch taken
                int offset = stmt.getOperands()[2]; // TODO: is this generic?
                try {
                    return Memory.getInstance().getStatementNoNotify(stmt.getAddress() + offset);
                } catch (AddressErrorException e) {
                    e.printStackTrace();
                }
            }
        }

        if (stmt.getInstruction() instanceof JAL) {
            // JAL b = (JAL) stmt.getInstruction();
            int offset = stmt.getOperands()[1];
            try {
                return Memory.getInstance().getStatementNoNotify(stmt.getAddress() + offset);
            } catch (AddressErrorException e) {
                e.printStackTrace();
            }
        }

        if (stmt.getInstruction() instanceof JALR) {
            // JALR b = (JALR) stmt.getInstruction();
            int newaddr = RegisterFile.getValue(stmt.getOperand(1)) + stmt.getOperand(2);
            try {
                return Memory.getInstance().getStatementNoNotify(newaddr);
            } catch (AddressErrorException e) {
                e.printStackTrace();
            }
        }

        return null;
    }

    private ProgramStatement instructionAfter(ProgramStatement current) {
        ProgramStatement nextInMem = null;
        try {
            // assume code segment only includes actual instructions
            nextInMem = Memory.getInstance().getStatementNoNotify(current.getAddress() + Instruction.INSTRUCTION_LENGTH);
        } catch (AddressErrorException e) {
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

    private Set<Integer> hasControlHazard() {
        Set<Integer> collisions = new HashSet<>();

        for (int i = CONTROL_HAZARD_DETECT; i <= CONTROL_HAZARD_RESOLVE; i++) {
            if (isBranchInstruction(currentPipeline[i])) {
                collisions.add(i);
            }
        }

        return collisions;
    }

    // probably better to use polymorphism with the operands themselves
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

        // TODO: ecalls currently not handled properly
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


        // TODO: ecalls currently not handled properly
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
            int rows = gui.getModel().getRowCount();

            gui.getModel().removeRow(rows-1);
            gui.getColors().forEach((map) -> {
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
        int rows = gui.getModel().getRowCount();
        Set<Integer> controlHazards = hasControlHazard();
        Set<Integer> dataHazards = hasDataHazard();

        Object[] newrow = new Object[STAGES+1];
        newrow[0] = rows+1;
        for (int i = 0; i < STAGES; i++) {
            StringBuilder sb = new StringBuilder();
            sb.append(statementToString(currentPipeline[i]));
            if (dataHazards.contains(i)) {
                sb.append(DATA_HAZARD_LABEL);
            }
            if (controlHazards.contains(i)) {
                sb.append(CONTROL_HAZARD_LABEL);
            }
            newrow[i+1] = sb.toString();
        }

        // write pipeline to table
        gui.getModel().addRow(newrow);

        // add color

        ArrayList<Map<Integer, Color>> colors = gui.getColors();

        // color for cycle column
        colors.get(0).putIfAbsent(rows, Color.LIGHT_GRAY);

        // control hazard
        for (int stage : controlHazards) {
            stage++; // +1 because of cycle column

            colors.get(stage).put(rows, Color.YELLOW);
        }

        // data hazard
        for (int stage : dataHazards) {
            stage++; // +1 because of cycle column

            // already has control hazard
            if (colors.get(stage).containsKey(rows)) {
                colors.get(stage).put(rows, Color.GREEN);
            } else {
                colors.get(stage).put(rows, Color.CYAN);
            }
        }
    }

    private int instructionsExecuted() {
        return backstepStack.size();
    }

    private int totalCyclesTaken() {
        return Math.max(1, gui.getModel().getRowCount());
    }

    private double speedup() {
        return (double) STAGES * instructionsExecuted() / totalCyclesTaken();
    }

    private double speedupWithoutHazards() {
        return (double) STAGES * instructionsExecuted() / (STAGES + instructionsExecuted() - 1);
    }

    private void updateSpeedupText() {
        gui.updateSpeedupText(instructionsExecuted(), speedup(), speedupWithoutHazards());
    }
}
