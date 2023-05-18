package rars.tools;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;

import rars.ProgramStatement;
import rars.riscv.hardware.AccessNotice;
import rars.riscv.hardware.AddressErrorException;
import rars.riscv.hardware.Memory;
import rars.riscv.hardware.MemoryAccessNotice;

import java.awt.*;
import java.util.Observable;

public class PipelineVisualizer extends AbstractToolAndApplication {
    // TODO: make back-button work

    private static final String NAME = "VAPOR";
    private static final String VERSION = "1.0 (Johannes Dittrich)";
    private static final String HEADING = "Visualizer for advanced pipelining on RARS";

    private JTable pipeline;
    private DefaultTableModel model;
    private int lastAddress = -1;

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
        
        String instructionName = stmt.getInstruction().getName();
        
        // insert into table
        model.addRow(new Object[] {instructionName, "", "", "", ""});
    }
}
