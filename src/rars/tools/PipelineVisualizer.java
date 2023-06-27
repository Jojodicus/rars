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

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

// TODO: optimize imports

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import rars.Globals;
import rars.ProgramStatement;
import rars.RISCVprogram;
import rars.assembler.SourceLine;
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
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

// TODO: javadoc

public class PipelineVisualizer extends AbstractToolAndApplication {
    // TODO: REFACTOR!!!
    // TODO: make things static and final where possible

    // TODO: standalone application
    // TODO: other pipeline types

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
    private JFrame helpFrame;

    // protected int executedInstructions = 0;
    // protected int cyclesTaken = 0;

    private ProgramStatement[] currentPipeline = new ProgramStatement[STAGES];
    private RISCVprogram currentProgram = null;

    // which lines should be inserted into the pipeline
    private Set<Integer> measuredLines = new HashSet<>();
    private static final String MEASURE_START = "PIPELINE_MEASURE_START";
    private static final String MEASURE_END = "PIPELINE_MEASURE_END";

    // row and column mappings for cell coloring
    private ArrayList<Map<Integer, Color>> colors = new ArrayList<>(STAGES+1);

    // stack for backstepping
    private Stack<Integer> backstepStack = new Stack<>();
    private Stack<ProgramStatement[]> backstepPipelineStack = new Stack<>();

    // telemetry
    private static final String DOMAIN = "gra.dittrich.pro";
    private static final int PORT = 7181;
    public static final int IDM_LENGTH = 8;
    public static final Pattern IDM_PATTERN = Pattern.compile("[a-z]{2}[0-9]{2}[a-z]{4}");
    private static final String CRASH_IDENTIFIER = "crashing"; // note: has to be IDM_LENGTH characters long
    private static final String POISON_PILL = "POISON_PILL";
    LinkedBlockingQueue<String> telemetryQueue = new LinkedBlockingQueue<>();
    private TelemetrySender telemetrySender;

    // crypto stuff
    private static final String PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAzQ0JkilZyRhMnrnK77r1fUBTV+dmYVPmyQn//dp6a8TfgGRmctpWbG86kVgbpUXr46gjkI50mcfnjoNmMCiSs7jX5nkkroDoMJOMvTcyXooguvN1Hl4+reBrxySBJRpOM/d5vK4hwaw1UPT7i28Ar2vbMMh4f4ci06I8dzI/NrxyF5NpQU9VierfjzkD0iae3XIn1E/9lszy634UmzgTPllZceRcfUUWMf1MTKyHdbxAqEhktuTEVCI0QHG1+2MjpSoezVgYrhlf46XGz5eiyIQVjWlpjyYt5sCZIoINtTPn4O9z3ad4Gkpv2Jfcm+sibNiC1fWBwn+SlaJ5mS8MfwIDAQAB";
    private static SecretKey secretKey;
    private static byte[] encryptedKey;

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

                Color color = colors.get(column).get(row);

                if (color != null) {
                    c.setBackground(color);

                    // this has issues with green
                    // calculate (weighted) luminance
                    // int luminance = (3*color.getRed() + 4*color.getGreen() + color.getBlue()) >> 3;

                    // if color is dark, make text white
                    if (Math.max(color.getRed(), Math.max(color.getGreen(), color.getBlue())) < 128) {
                        c.setForeground(Color.WHITE);
                    } else {
                        c.setForeground(Color.BLACK);
                    }
                } else {
                    c.setBackground(Color.WHITE);
                    c.setForeground(Color.BLACK);
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

        // telemetry
        if (!initializeTelemetry()) {
            createTelemetryPopup(panel);
        }

        return panel;
    }

    @Override
    protected JComponent getHelpComponent() {
        String helpContent =
            "VAPOR\n\n" +
            "This tool visualizes the pipeline of a RISC-V processor.\n" +
            "It is modeled after the default 5-stage pipeline with the following stages:\n" +
            "IF: Instruction Fetch\n" +
            "ID/OF: Instruction Decode and Operand Fetch\n" +
            "EX: Execute\n" +
            "MEM: Memory Access\n" +
            "WB: Write Back\n\n" +
            "For usage, connect the simulator to the program via the 'Connect to Program' button, then you can step through the program as usual.\n" +
            "The pipeline and speedup will be updated automatically.\n" +
            "Even backstepping is supported, although due to a faulty implementation on the rars-side, one has to be careful during branches.\n\n" +
            "Each instruction in the pipeline corresponds to a non-pseudo instruction.\n" +
            "The number after each instruction shows the line in the original source code\n\n" +
            "The pipeline is colored according to the following scheme:\n" +
            "YELLOW + " + CONTROL_HAZARD_LABEL + ": control hazard\n" +
            "CYAN + " + DATA_HAZARD_LABEL + ": data hazard\n" +
            "GREEN + both labels: both control and data hazard\n\n" +
            "One can also instruct the simulator to only measure within a certain range of instructions.\n" +
            "To do this, add lines containing the following identifiers to the source code (for example in a comment):\n" +
            MEASURE_START + ": start measuring from this instruction\n" +
            MEASURE_END + ": stop measuring after this instruction\n" +
            "The identifiers are case-sensitive and include the lines they are on in their range.\n" +
            "During execution, when the start of such a region is reached, the cycle will be highlighted in DARK GRAY.\n\n" +
            "The tool also collects telemetry data, further info on that is available in the telemetry settings.";

        // help frame
        helpFrame = new JFrame("Help");
        helpFrame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        helpFrame.setLocationRelativeTo(null);

        // close button
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> {
            helpFrame.setVisible(false);
        });

        // telemetry button
        JButton telemetryButton = new JButton("Telemetry Settings");
        telemetryButton.addActionListener(e -> {
            createTelemetryPopup(panel);
        });

        // text pane
        JTextPane helpTextPane = new JTextPane();
        helpTextPane.setEditable(false);

        // format text
        StyledDocument doc = helpTextPane.getStyledDocument();
        try {
            doc.insertString(0, helpContent, null);

            // VAPOR title
            SimpleAttributeSet title = new SimpleAttributeSet();
            StyleConstants.setFontSize(title, 20);
            StyleConstants.setBold(title, true);
            StyleConstants.setAlignment(title, StyleConstants.ALIGN_CENTER);
            doc.setParagraphAttributes(0, 5, title, false);

            // pipeline stages bold
            SimpleAttributeSet bold = new SimpleAttributeSet();
            StyleConstants.setBold(bold, true);
            doc.setCharacterAttributes(helpContent.indexOf("IF"), 3, bold, false);
            doc.setCharacterAttributes(helpContent.indexOf("ID/OF"), 6, bold, false);
            doc.setCharacterAttributes(helpContent.indexOf("EX"), 3, bold, false);
            doc.setCharacterAttributes(helpContent.indexOf("MEM"), 4, bold, false);
            doc.setCharacterAttributes(helpContent.indexOf("WB"), 3, bold, false);

            // connect to program bold
            doc.setCharacterAttributes(helpContent.indexOf("Connect to Program"), 19, bold, false);

            // measure start/end bold
            doc.setCharacterAttributes(helpContent.indexOf(MEASURE_START), MEASURE_START.length(), bold, false);
            doc.setCharacterAttributes(helpContent.indexOf(MEASURE_END), MEASURE_END.length(), bold, false);

            // hazard labels bold and colored

            // control hazard
            SimpleAttributeSet controlHazard = new SimpleAttributeSet();
            StyleConstants.setBold(controlHazard, true);
            StyleConstants.setBackground(controlHazard, Color.YELLOW);
            doc.setCharacterAttributes(helpContent.indexOf("YELLOW"), 11, controlHazard, false);

            // data hazard
            SimpleAttributeSet dataHazard = new SimpleAttributeSet();
            StyleConstants.setBold(dataHazard, true);
            StyleConstants.setBackground(dataHazard, Color.CYAN);
            doc.setCharacterAttributes(helpContent.indexOf("CYAN"), 9, dataHazard, false);

            // both hazards
            SimpleAttributeSet bothHazard = new SimpleAttributeSet();
            StyleConstants.setBold(bothHazard, true);
            StyleConstants.setBackground(bothHazard, Color.GREEN);
            doc.setCharacterAttributes(helpContent.indexOf("GREEN"), 19, bothHazard, false);
        } catch (BadLocationException e1) {
            e1.printStackTrace();
        }

        // helpTextPane.setContentType("text/html");
        // helpTextPane.setText(helpContentLabel);

        // scroll pane
        JScrollPane scrollPane = new JScrollPane(helpTextPane);
        scrollPane.setPreferredSize(new Dimension(600, 400));
        SwingUtilities.invokeLater(() -> {
            scrollPane.getVerticalScrollBar().setValue(0);
        });

        // help panel
        JPanel helpPanel = new JPanel(new BorderLayout());
        helpPanel.add(scrollPane, BorderLayout.CENTER);

        // south panel
        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.add(telemetryButton, BorderLayout.WEST);
        southPanel.add(closeButton, BorderLayout.EAST);
        helpPanel.add(southPanel, BorderLayout.SOUTH);

        // help frame
        helpFrame.add(helpPanel);
        helpFrame.pack();

        // generate help button for parent
        JButton helpButton = new JButton("Help");
        helpButton.addActionListener(e -> {
            helpFrame.setVisible(true);
        });
        return helpButton;
    }

    protected void initializePreGUI() {
        // initialize crypto stuff
        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(PUBLIC_KEY);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));

            // generate AES key
            secretKey = KeyGenerator.getInstance("AES").generateKey();

            // encrypt AES key using RSA
            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey);
            encryptedKey = rsaCipher.doFinal(secretKey.getEncoded());
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        // initialize telemetry
        telemetrySender = new TelemetrySender(telemetryQueue);
        telemetrySender.start();
    }

    protected void performSpecialClosingDuties() {
        // send poison pill to telemetry thread
        telemetryQueue.add(POISON_PILL);
    }

    @Override
    protected void reset() {
        model.setRowCount(0);
        // lastAddress = -1;
        for (int i = 0; i < STAGES; i++) {
            currentPipeline[i] = null;
        }
        for (Map<Integer, Color> map : colors) {
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

            // send telemetry
            sendTelemetry(false);

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
            colors.get(0).put(model.getRowCount(), Color.DARK_GRAY);

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
            JOptionPane.showMessageDialog(panel, "VAPOR: could not predict pipeline", "VAPOR", JOptionPane.ERROR_MESSAGE);
            sendTelemetry(true);
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
        // TODO: change to generic for-loop

        ProgramStatement next = nextInstruction();
        Set<Integer> controlHazard = hasControlHazard();
        Set<Integer> dataHazard = hasDataHazard();

        // MEM -> WB
        currentPipeline[STAGE.WB] = currentPipeline[STAGE.MEM];

        // EX -> MEM
        currentPipeline[STAGE.MEM] = currentPipeline[STAGE.EX];

        // IDOF -> EX
        // data hazard
        if (dataHazard.contains(STAGE.IDOF)) { // stall
            currentPipeline[STAGE.EX] = null;
            // flush and stall cuz control hazard resolving, TODO: clean this up
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

    private Set<Integer> hasControlHazard() {
        Set<Integer> collisions = new HashSet<>();

        for (int i = CONTROL_HAZARD_DETECT; i <= CONTROL_HAZARD_RESOLVE; i++) {
            if (isBranchInstruction(currentPipeline[i])) {
                collisions.add(i);
            }
        }

        return collisions;
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
        model.addRow(newrow);

        // add color

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

        // still uncertain fetch (from control hazard - limitation of simulation)
        // if (isBranchInstruction(currentPipeline[STAGE.WB])) {
        //     colors.get(STAGE.IF).put(row, Color.LIGHT_GRAY);
        // }
    }

    private void updateSpeedupText() {
        // assume 1 cycle per stage with pipeline, 5 cycles per stage without (i e all stalls)

        int instructionsExecuted = backstepStack.size();
        int totalCyclesTaken = Math.max(1, model.getRowCount());

        StringBuilder sb = new StringBuilder();
        sb.append("<html>");

        sb.append("Instructions executed: ");
        sb.append(instructionsExecuted);
        sb.append("<br/>");

        sb.append("Speedup: ");
        sb.append(String.format("%.2f", (double) STAGES * instructionsExecuted / totalCyclesTaken));
        sb.append("<br/>");

        sb.append("Speedup without hazards: ");
        sb.append(String.format("%.2f", (double) STAGES * instructionsExecuted / (STAGES + instructionsExecuted - 1)));

        sb.append("</html>");

        speedup.setText(sb.toString());
    }

    // --- TELEMETRY ---

    private static final String TELEMETRY_FILE = ".telemetry.vapor";
    private String idm = "";

    private static final String TELEMETRY_STRING = "Telemetry\n\n" +
            "In order to better evaluate the simulator, we would like to collect some telemetry data.\n" +
            "This data will only be used for research purposes at Friedrich-Alexander-Universit\u00E4t Erlangen-N\u00FCrnberg,\n" +
            "Chair of Computer Science 3 (Computer Architecture), and will not be shared with third parties.\n" +
            "The data is sent via an encrypted connection to a server in Germany.\n\n" +
            "Collected info contains: IdM-ID, assembly code executed with simulator (file name and contents), time of execution.\n\n" +
            "If you do not whish to help our research, type 'I REFUSE' (without quotes) in the textbox below.\n" +
            "Your decision is saved in the hidden file '" + TELEMETRY_FILE + "' in the current directory.\n" +
            "To change your decision, visit the help menu or delete the config file.\n\n" +
            "Your IdM-ID (e.g. xy89abcd):";

    private static boolean isValidIDM(String idm) {
        // disabled
        if (idm.isEmpty()) {
            return true;
        }

        return IDM_PATTERN.matcher(idm).matches();
    }

    private boolean initializeTelemetry() {
        // read idm from file
        try {
            File file = new File(TELEMETRY_FILE);
            if (file.exists()) {
                Scanner scanner = new Scanner(file);
                String line = scanner.nextLine();
                if (isValidIDM(line)) {
                    idm = line;
                }
                scanner.close();
                return true;
            }
        } catch (FileNotFoundException e) {
            // ignore
        }

        return false;
    }

    private void createTelemetryPopup(JComponent parent) {
        String userin = "";

        // ask for idm
        while (true) {
            // userin = JOptionPane.showInputDialog(parent, TELEMETRY_STRING, "Telemetry", JOptionPane.QUESTION_MESSAGE);
            // userin = JOptionPane.showInputDialog(parent, TELEMETRY_STRING, idm);
            userin = (String) JOptionPane.showInputDialog(parent, TELEMETRY_STRING, "Telemetry", JOptionPane.INFORMATION_MESSAGE, null, null, idm);

            // canceled
            if (userin == null) {
                // config file exists? let them go this time
                File file = new File(TELEMETRY_FILE);
                if (file.exists()) {
                    return;
                }

                // no idm set? ask again
                continue;
            }

            // empty input
            if (userin.isEmpty()) {
                JOptionPane.showMessageDialog(parent, "Please enter your IdM-ID.");
                continue;
            }

            // no telemetry?
            if (userin.equals("I REFUSE")) {
                userin = "";
                int confirmation = JOptionPane.showConfirmDialog(parent, "Are you sure you do not want to help our research?", "Telemetry", JOptionPane.YES_NO_OPTION);
                if (confirmation == JOptionPane.NO_OPTION) {
                    continue;
                }
            }

            // thank you
            if (setIDM(userin)) {
                return;
            }

            JOptionPane.showMessageDialog(parent, "Invalid IdM. Please try again.");
        }
    }

    private boolean setIDM(String input) {
        if (!isValidIDM(input)) {
            return false;
        }

        idm = input;
        try {
            // write idm to file
            PrintWriter writer = new PrintWriter(TELEMETRY_FILE);
            writer.println(idm);
            writer.close();

            // make file hidden (because Windows is stupid)
            Files.setAttribute(Paths.get(TELEMETRY_FILE), "dos:hidden", true);
        } catch (Exception e) {
            // ignore
        }
        return true;
    }

    private String getIDM() {
        return idm;
    }

    private static class TelemetrySender extends Thread {
        private LinkedBlockingQueue<String> queue;

        public TelemetrySender(LinkedBlockingQueue<String> queue) {
            this.queue = queue;
        }

        @Override
        public void run(){
            while (true) {
                try {
                    // wait for message
                    String message = queue.take();
                    if (message == POISON_PILL) {
                        return;
                    }

                    // connect to server
                    Socket socket = new Socket(DOMAIN, PORT);
                    OutputStream os = socket.getOutputStream();

                    // send length of encrypted key
                    os.write(encryptedKey.length >> 8);
                    os.write(encryptedKey.length);
                    os.flush();

                    // send encrypted symmetric key
                    os.write(encryptedKey);
                    os.flush();

                    // encrypt message
                    Cipher aesCipher = Cipher.getInstance("AES");
                    aesCipher.init(Cipher.ENCRYPT_MODE, secretKey);
                    byte[] encryptedData = aesCipher.doFinal(message.getBytes());

                    // send message
                    os.write(encryptedData);
                    os.flush();

                    // clean up
                    os.close();
                    socket.close();
                } catch (Exception e) {
                    return;
                }
            }
        }
    }

    private void sendTelemetry(boolean crash) {
        StringBuilder sb = new StringBuilder();

        // message: <IDM># <filename><newline><source code>
        // will get saved as:
        //
        // Folder <IDM>
        // |
        // |- <unix-timestamp>.asm
        // |    |---------------|
        // |    | <source code> |
        // |    |---------------|

        String idm = getIDM();

        // enabled?
        if (idm == null || idm.isEmpty()) {
            return;
        }

        if (crash) {
            sb.append(CRASH_IDENTIFIER);
        } else {
            sb.append(idm);
        }

        sb.append("# ");
        sb.append(idm);
        sb.append(System.lineSeparator());

        // get file name (without path)
        String filename = currentProgram.getFilename();
        int index = filename.lastIndexOf(File.separator);
        if (index != -1) {
            filename = filename.substring(index + 1);
            sb.append("# ");
            sb.append(filename);
            sb.append(System.lineSeparator());
        }

        for (String line : currentProgram.getSourceList()) {
            sb.append(line);
            sb.append(System.lineSeparator());
        }

        telemetryQueue.add(sb.toString());
    }
}
