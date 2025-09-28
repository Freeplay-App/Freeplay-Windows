import javax.swing.*;
import javax.swing.plaf.basic.BasicSliderUI;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.*;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;

public class canvasex extends JFrame {

    // ====== Canvas / Shapes
    interface DrawingShape { void draw(Graphics2D g); }

    static class LineShape implements DrawingShape {
        Point a, b; Color color; int size;
        LineShape(Point a, Point b, Color c, int s) { this.a=a; this.b=b; this.color=c; this.size=s; }
        @Override public void draw(Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.setStroke(new BasicStroke(size, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(a.x, a.y, b.x, b.y);
        }
    }

    static class RectShape implements DrawingShape {
        Point a, b; Color color; int size;
        RectShape(Point a, Point b, Color c, int s) { this.a=a; this.b=b; this.color=c; this.size=s; }
        @Override public void draw(Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.setStroke(new BasicStroke(size));
            g.drawRect(Math.min(a.x,b.x), Math.min(a.y,b.y), Math.abs(a.x-b.x), Math.abs(a.y-b.y));
        }
    }

    static class OvalShape implements DrawingShape {
        Point a, b; Color color; int size;
        OvalShape(Point a, Point b, Color c, int s) { this.a=a; this.b=b; this.color=c; this.size=s; }
        @Override public void draw(Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.setStroke(new BasicStroke(size));
            g.drawOval(Math.min(a.x,b.x), Math.min(a.y,b.y), Math.abs(a.x-b.x), Math.abs(a.y-b.y));
        }
    }

    static class TextShape implements DrawingShape {
        String text; Point pos; Color color; Font font;
        TextShape(String t, Point p, Color c, Font f) { this.text=t; this.pos=p; this.color=c; this.font=f; }
        @Override public void draw(Graphics2D g) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(color);
            g.setFont(font);
            g.drawString(text, pos.x, pos.y);
        }
    }

    // ===== soft shadow helper (simple, high-quality look without heavy blur ops)
    private static void paintSoftShadow(Graphics2D g2, Shape s, int blurRadius, float baseAlpha) {
        if (blurRadius <= 0) return;
        Rectangle bounds = s.getBounds();
        // Save transform & composite
        AffineTransform oldT = g2.getTransform();
        Composite oldC = g2.getComposite();

        // We'll render several stacked translucent fills slightly offset to simulate blur
        // It's cheap and looks smooth for UI shadows.
        for (int i = blurRadius; i >= 1; i--) {
            float a = baseAlpha * (i / (float)(blurRadius + 1)) * 0.9f;
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, a))));
            AffineTransform t = AffineTransform.getTranslateInstance(0, i/2.0);
            g2.transform(t);
            g2.fill(s);
            g2.setTransform(oldT);
        }
        g2.setComposite(oldC);
    }

    static class CanvasView extends JComponent {
        // Daten
        List<DrawingShape> shapes = new ArrayList<>();

        // View-Transform
        double scale = 1.0;
        double offX = 0, offY = 0;

        // Interaktion
        Point lastWorld = null;
        Point dragStartWorld = null;
        Point dragNowWorld = null;
        boolean panning = false;
        int panLastX, panLastY;

        // Tools
        enum Mode { PEN, LINE, RECT, OVAL, TEXT }
        Mode mode = Mode.PEN;

        // Stil
        Color color = Color.BLACK;     // allgemeine Zeichenfarbe
        Color textColor = Color.BLACK; // Text-spezifische Farbe (für Text-Panel)
        int penSize = 4;
        String fontFamily = "Arial";
        int fontSize = 24;
        int fontStyle = Font.PLAIN;

        // Text-Tippen
        boolean typing = false;
        Point textStartWorld = null;
        StringBuilder textBuffer = new StringBuilder();

        CanvasView() {
            setFocusable(true);

            addMouseWheelListener(this::onWheel);

            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    if (SwingUtilities.isRightMouseButton(e)) {
                        panning = true; panLastX = e.getX(); panLastY = e.getY();
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                        return;
                    }
                    Point w = toWorld(e.getPoint());
                    if (mode == Mode.TEXT) {
                        typing = true;
                        textBuffer.setLength(0);
                        textStartWorld = w;
                        repaint();
                        return;
                    }
                    dragStartWorld = w;
                    lastWorld = w;
                }

                @Override public void mouseReleased(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        panning = false; setCursor(Cursor.getDefaultCursor());
                        return;
                    }
                    if (dragStartWorld == null) return;
                    Point w = toWorld(e.getPoint());
                    commitShape(dragStartWorld, w);
                    dragStartWorld = null;
                    dragNowWorld = null;
                    repaint();
                }
            });

            addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseDragged(MouseEvent e) {
                    if (panning) {
                        offX += e.getX() - panLastX;
                        offY += e.getY() - panLastY;
                        panLastX = e.getX(); panLastY = e.getY();
                        repaint();
                        return;
                    }
                    Point w = toWorld(e.getPoint());
                    if (mode == Mode.PEN && lastWorld != null) {
                        shapes.add(new LineShape(lastWorld, w, color, penSize));
                        lastWorld = w;
                        repaint();
                    } else {
                        dragNowWorld = w; // Vorschau
                        repaint();
                    }
                }
            });

            addKeyListener(new KeyAdapter() {
                @Override public void keyTyped(KeyEvent e) {
                    if (!typing) return;
                    char c = e.getKeyChar();
                    if (c == KeyEvent.VK_ENTER) {
                        drawText(textBuffer.toString(), textStartWorld);
                        typing = false;
                        textStartWorld = null;
                        textBuffer.setLength(0);
                        repaint();
                    } else if (c == KeyEvent.VK_BACK_SPACE) {
                        if (textBuffer.length() > 0) textBuffer.setLength(textBuffer.length() - 1);
                        repaint();
                    } else {
                        textBuffer.append(c);
                        repaint();
                    }
                }
            });
        }

        // Koordinaten
        Point toWorld(Point s) {
            try {
                AffineTransform at = viewTransform();
                AffineTransform inv = at.createInverse();
                Point p = new Point();
                inv.transform(s, p);
                return p;
            } catch (Exception ex) { return new Point(s); }
        }
        AffineTransform viewTransform() {
            AffineTransform at = new AffineTransform();
            at.translate(offX, offY);
            at.scale(scale, scale);
            return at;
        }

        // Zoom
        void onWheel(MouseWheelEvent e) {
            double prev = scale;
            double factor = (e.getPreciseWheelRotation() < 0) ? 1.1 : 0.9;
            scale = Math.max(0.1, Math.min(10.0, scale * factor));
            Point p = e.getPoint();
            offX = p.x - (p.x - offX) * (scale / prev);
            offY = p.y - (p.y - offY) * (scale / prev);
            repaint();
        }

        // Zeichnen / Commit
        void commitShape(Point a, Point b) {
            switch (mode) {
                case LINE -> shapes.add(new LineShape(a, b, color, penSize));
                case RECT -> shapes.add(new RectShape(a, b, color, penSize));
                case OVAL -> shapes.add(new OvalShape(a, b, color, penSize));
                default -> {}
            }
        }
        void drawText(String text, Point pos) {
            Font f = new Font(fontFamily, fontStyle, fontSize);
            shapes.add(new TextShape(text, pos, textColor, f));
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.transform(viewTransform());

            // gespeicherte Shapes
            for (DrawingShape s : shapes) s.draw(g2);

            // Vorschau
            if (dragStartWorld != null && dragNowWorld != null && mode != Mode.PEN && mode != Mode.TEXT) {
                g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 180));
                g2.setStroke(new BasicStroke(penSize));
                switch (mode) {
                    case LINE -> g2.drawLine(dragStartWorld.x, dragStartWorld.y, dragNowWorld.x, dragNowWorld.y);
                    case RECT -> g2.drawRect(Math.min(dragStartWorld.x, dragNowWorld.x), Math.min(dragStartWorld.y, dragNowWorld.y),
                            Math.abs(dragNowWorld.x - dragStartWorld.x), Math.abs(dragNowWorld.y - dragStartWorld.y));
                    case OVAL -> g2.drawOval(Math.min(dragStartWorld.x, dragNowWorld.x), Math.min(dragStartWorld.y, dragNowWorld.y),
                            Math.abs(dragNowWorld.x - dragStartWorld.x), Math.abs(dragNowWorld.y - dragStartWorld.y));
                }
            }

            // Live-Tippen
            if (typing && textStartWorld != null) {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(textColor);
                g2.setFont(new Font(fontFamily, fontStyle, fontSize));
                String preview = textBuffer.toString() + "\u2588";
                g2.drawString(preview, textStartWorld.x, textStartWorld.y);
            }
            g2.dispose();
        }
    }

    // ====== UI ======
    final CanvasView cv = new CanvasView();

    // Top UI
    private JPanel topStack;     // enthält topBar + dropHost
    private JPanel topBar;       // Row mit Buttons
    private JPanel dropHost;     // „Parkplatz“ für Panels (bleibt 0px hoch)
    private JPanel colorPanel;   // HSV + Pen-Size (Mitte/pencil)
    private JPanel modePanel;    // Mode-Auswahl (links/ecke)
    private JPanel textPanel;    // Text-Optionen (rechts/scale)

    // Runde Hauptbuttons
    private Roundheadbutton btnModes, btnColor, btnText;

    // === Overlay/Popup über GlassPane ===
    private JComponent glass;
    private JPanel visibleDropdown = null;
    private JComponent visibleAnchor = null;
    private int visibleW = 0, visibleH = 0;

    public canvasex() {
        super("Freeplay"); // App-Name
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        try {
            Image icon = Toolkit.getDefaultToolkit().getImage("icon.png");
            setIconImage(icon);
        } catch (Exception ignored) {}

        setLayout(new BorderLayout());

        // IMPORTANT: initialize glass (GlassPane) FIRST so hideAllDropdowns() can safely use it
        glass = new JPanel(null);
        glass.setOpaque(false);
        glass.setLayout(null);
        glass.setVisible(false);
        setGlassPane(glass);

        // ===== Top-Bereich (Stack) =====
        topStack = new JPanel(new BorderLayout());
        add(topStack, BorderLayout.NORTH);

        // Top-Bar: links "Neu", zentriert 3 runde Buttons
        topBar = new JPanel(new BorderLayout());
        topBar.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
        topBar.setBackground(new Color(30, 30, 30)); // dunkel
        topStack.add(topBar, BorderLayout.NORTH);

        // Ganz links: Reset
        JButton clearBtn = new JButton("New");
        clearBtn.addActionListener(e -> {
            cv.shapes.clear();
            cv.repaint();
            cv.requestFocusInWindow();
        });
        JPanel leftBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftBox.setOpaque(false);
        leftBox.add(clearBtn);
        topBar.add(leftBox, BorderLayout.WEST);

        // Mitte: 3 runde Buttons (ecke /pencil / scale)
        JPanel centerRound = new JPanel();
        centerRound.setOpaque(false);
        centerRound.setLayout(new FlowLayout(FlowLayout.CENTER, 18, 0));

        btnModes = new Roundheadbutton("icons/ecke.svg", 20);
        btnColor = new Roundheadbutton("icons/pencil.svg", 20);
        btnText  = new Roundheadbutton("icons/scale.svg", 20);

        centerRound.add(btnModes);
        centerRound.add(btnColor);
        centerRound.add(btnText);
        topBar.add(centerRound, BorderLayout.CENTER);

        // dropHost (bleibt 0 Höhe, Panels werden dort nur geparkt)
        dropHost = new JPanel(null) { @Override public boolean isOpaque() { return false; } };
        dropHost.setPreferredSize(new Dimension(10, 0)); // dauerhaft eingefahren
        topStack.add(dropHost, BorderLayout.CENTER);

        // ===== Dropdown-Panels bauen =====
        colorPanel = buildColorPanel(); // HSV + PenSize
        modePanel  = buildModePanel();  // runde Mode-Buttons
        textPanel  = buildTextPanel();  // Font + Stil + Größe + HSV für Text

        // Im dropHost  (unsichtbar)
        dropHost.add(colorPanel);
        dropHost.add(modePanel);
        dropHost.add(textPanel);

        // safe: hide dropdowns (glass already initialized)
        hideAllDropdowns();

        // Klicks außerhalb schließen das Popup, innerhalb NICHT
        glass.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (visibleDropdown == null) return;
                Point pInPopup = SwingUtilities.convertPoint(glass, e.getPoint(), visibleDropdown);
                if (!visibleDropdown.contains(pInPopup)) hideAllDropdowns();
            }
        });

        // Button-Logik → Overlay direkt unter dem jeweiligen Button
        btnModes.addActionListener(e -> toggleDropdownOverlay(modePanel, btnModes, 330, 120));
        btnColor.addActionListener(e -> toggleDropdownOverlay(colorPanel, btnColor, 320,140));
        btnText.addActionListener(e -> toggleDropdownOverlay(textPanel,  btnText,  360, 240));

        // ===== Canvas als Center =====
        JScrollPane scroller = new JScrollPane(cv);
        add(scroller, BorderLayout.CENTER);

        // Bei Resize Popup neu positionieren
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                reanchorVisibleDropdowns();
            }
        });
    }

    // ===== Utils: Icons / Round Buttons / Dropdowns =====
    private static ImageIcon loadSvgIcon(String path, int size) {
        try {
            byte[] svgBytes = Files.readAllBytes(Paths.get(path));
            TranscoderInput input = new TranscoderInput(new ByteArrayInputStream(svgBytes));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            TranscoderOutput output = new TranscoderOutput(out);

            PNGTranscoder transcoder = new PNGTranscoder();
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) size);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) size);
            transcoder.transcode(input, output);

            out.flush();
            BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
            return new ImageIcon(img);
        } catch (Exception e) {
            // fallback: return empty icon to avoid NPE
            // e.printStackTrace();
            return new ImageIcon(new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB));
        }
    }

    // ==== Einheitliche runde Buttons mit Schatten ====
    static class RoundButton extends JButton {
        private boolean hover = false;

        RoundButton(String svgPath, int size) {
            super(loadSvgIcon(svgPath, size));
            setPreferredSize(new Dimension(size + 14, size + 14));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited (MouseEvent e) { hover = false; repaint(); }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            Shape circle = new Ellipse2D.Double(2,2,w-4,h-4);

            paintSoftShadow(g2, circle, 8, 0.20f);
            g2.setColor(new Color(30,30,30,220)); // dunkel Hintergrund für Buttons
            g2.fill(circle);

            if (hover) {
                g2.setStroke(new BasicStroke(2f));
                g2.setColor(new Color(60,120,255,200));
                g2.draw(circle);
            }
            g2.dispose();
            super.paintComponent(g);
        }

        @Override public boolean contains(int x, int y) {
            int r = Math.min(getWidth(), getHeight()) / 2;
            int cx = getWidth()/2, cy = getHeight()/2;
            int dx = x - cx, dy = y - cy;
            return dx*dx + dy*dy <= r*r;
        }
    }

    // Farb-Button (klein, rund, mit Hover + Shadow)
    static class ColorButton extends JButton {
        private boolean hover = false;
        private final Color baseColor;

        ColorButton(Color color, int size) {
            this.baseColor = color;
            setPreferredSize(new Dimension(size, size));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e)  { hover = false; repaint(); }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            Shape circle = new Ellipse2D.Double(2,2,w-4,h-4);

            paintSoftShadow(g2, circle, 6, 0.22f);
            g2.setColor(baseColor);
            g2.fill(circle);

            if (hover) {
                g2.setStroke(new BasicStroke(2f));
                g2.setColor(new Color(60,120,255,200));
                g2.draw(circle);
            }
            g2.dispose();
        }

        @Override public boolean contains(int x, int y) {
            int r = Math.min(getWidth(), getHeight())/2;
            int cx = getWidth()/2, cy = getHeight()/2;
            int dx = x - cx, dy = y - cy;
            return dx*dx + dy*dy <= r*r;
        }
    }

    // Roundheadbutton (Hauptbuttons oben) reuse RoundButton
    static class Roundheadbutton extends RoundButton {
        Roundheadbutton(String svgPath, int size) { super(svgPath, size); }
    }

    // ==== Abgerundetes Panel mit Shadow + subtle background ====
    static class RoundedPanel extends JPanel {
        private final int arc;
        RoundedPanel(int arc) {
            this.arc = arc;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
            setLayout(new GridBagLayout());
        }
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Shape rr = new RoundRectangle2D.Double(6, 6, Math.max(getWidth()-12,0), Math.max(getHeight()-12,0), arc, arc);

            paintSoftShadow(g2, rr, 14, 0.20f);
            // slightly translucent dark background (since you asked dark)
            g2.setColor(new Color(18,18,20, 230));
            g2.fill(rr);

            // subtle inner border
            g2.setColor(new Color(255,255,255,10));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(rr);

            g2.dispose();
            super.paintComponent(g);
        }
    }

    // ==== ShadowSlider with custom painting (rounded thumb + rounded track) ====
    static class ShadowSlider extends JSlider {
        ShadowSlider(int min, int max, int val) {
            super(min, max, val);
            setOpaque(false);
            // Provide a BasicSliderUI that doesn't draw default thumb/track so we can custom paint
            setUI(new BasicSliderUI(this) {
                @Override public void paintTrack(Graphics g) { /* skip default */ }
                @Override public void paintThumb(Graphics g) { /* skip default */ }
            });
            setFocusable(false);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int trackH = 6;
            int left = 10;
            int right = w - 10;
            int trackY = h/2 - trackH/2;

            // track background
            RoundRectangle2D track = new RoundRectangle2D.Double(left, trackY, right-left, trackH, trackH, trackH);
            g2.setColor(new Color(90,90,95,180));
            g2.fill(track);

            // filled portion
            double range = getMaximum() - getMinimum();
            double pos = (getValue() - getMinimum()) / (range <= 0 ? 1.0 : range);
            int filledW = left + (int)((right-left) * pos);
            RoundRectangle2D filled = new RoundRectangle2D.Double(left, trackY, Math.max(2, filledW-left), trackH, trackH, trackH);
            g2.setColor(new Color(60,120,255,200));
            g2.fill(filled);

            // thumb position
            int thumbX = filledW;
            int thumbY = h / 2;
            Ellipse2D thumb = new Ellipse2D.Double(thumbX - 10, thumbY - 10, 20, 20);

            // shadow
            paintSoftShadow(g2, thumb, 8, 0.25f);

            // thumb
            g2.setColor(Color.WHITE);
            g2.fill(thumb);
            g2.setColor(new Color(150,150,150,200));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(thumb);

            g2.dispose();
        }
    }

    // ==== Overlay logic ====
    private void toggleDropdownOverlay(JPanel panel, JComponent anchorBtn, int prefW, int prefH) {
        boolean willShow = (visibleDropdown != panel);
        hideAllDropdowns(); // clean glass

        if (willShow) {
            Container parent = panel.getParent();
            if (parent != null) parent.remove(panel);

            panel.setSize(prefW, prefH);
            Point pGL = calcPopupLocation(anchorBtn, prefW, prefH, 8);
            panel.setLocation(pGL);

            if (glass == null) {
                glass = (JComponent) getRootPane().getGlassPane();
                glass.setLayout(null);
            }

            glass.add(panel);
            panel.setVisible(true);
            glass.setVisible(true);

            visibleDropdown = panel;
            visibleAnchor = anchorBtn;
            visibleW = prefW; visibleH = prefH;

            glass.revalidate();
            glass.repaint();
            cv.requestFocusInWindow();
        }
    }

    private Point calcPopupLocation(JComponent anchor, int prefW, int prefH, int gap) {
        Point aOnGlass = SwingUtilities.convertPoint(anchor, 0, 0, glass);
        int x = aOnGlass.x + anchor.getWidth()/2 - prefW/2;
        int y = aOnGlass.y + anchor.getHeight() + gap;
        x = Math.max(8, Math.min(x, glass.getWidth() - prefW - 8));
        y = Math.max(8, Math.min(y, glass.getHeight() - prefH - 8));
        return new Point(x, y);
    }

    private void hideAllDropdowns() {
        if (visibleDropdown != null) {
            if (glass != null) {
                glass.remove(visibleDropdown);
            }
            if (dropHost != null) dropHost.add(visibleDropdown);
            visibleDropdown.setVisible(false);
        }
        visibleDropdown = null;
        visibleAnchor = null;
        visibleW = visibleH = 0;

        if (glass != null) {
            glass.setVisible(false);
            glass.revalidate();
            glass.repaint();
        }

        setDropHostHeight(0);
    }

    private void reanchorVisibleDropdowns() {
        if (visibleDropdown != null && visibleAnchor != null) {
            Point p = calcPopupLocation(visibleAnchor, visibleW, visibleH, 8);
            visibleDropdown.setLocation(p);
            if (glass != null) {
                glass.revalidate();
                glass.repaint();
            }
        } else {
            setDropHostHeight(0);
        }
    }

    private void setDropHostHeight(int h) {
        if (dropHost != null) {
            dropHost.setPreferredSize(new Dimension(10, 0));
            topStack.revalidate();
        }
    }

    // ====== Panels bauen ======

    // Mitte: Farbwahl-Buttons + Strichstärke
    private JPanel buildColorPanel() {
        RoundedPanel p = new RoundedPanel(20);
        p.setLayout(new BorderLayout(8, 8));

        // colors row
        JPanel colorsRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        colorsRow.setOpaque(false);

        Color[] colors = {
            Color.BLACK, Color.RED, Color.BLUE, Color.GREEN,
            Color.YELLOW, Color.ORANGE, Color.WHITE
        };
        String[] names = {
            "Schwarz", "Rot", "Blau", "Grün", "Gelb", "Orange", "Weiß"
        };

        for (int i = 0; i < colors.length; i++) {
            final Color c = colors[i];
            ColorButton btn = new ColorButton(c, 34);
            btn.setToolTipText(names[i]);
            btn.addActionListener(e -> {
                cv.color = c;
                cv.requestFocusInWindow();
                cv.repaint();
            });
            colorsRow.add(btn);
        }

        // stroke row (uses ShadowSlider)
        JPanel strokeRow = new JPanel(new BorderLayout(6, 0));
        strokeRow.setOpaque(false);
        JLabel lw = new JLabel(new ImageIcon("fett.png"));
        ShadowSlider sw = new ShadowSlider(1, 64, cv.penSize);
        sw.setPreferredSize(new Dimension(220, 36));
        strokeRow.add(lw, BorderLayout.WEST);
        strokeRow.add(sw, BorderLayout.CENTER);

        sw.addChangeListener(e -> {
            cv.penSize = sw.getValue();
            cv.requestFocusInWindow();
            cv.repaint();
        });

        p.add(colorsRow, BorderLayout.CENTER);
        p.add(strokeRow, BorderLayout.SOUTH);

        p.setVisible(false);
        return p;
    }

    // Links: Mode-Auswahl (Buttons mit Icons)
    private JPanel buildModePanel() {
        RoundedPanel p = new RoundedPanel(20);
        p.setLayout(new FlowLayout(FlowLayout.CENTER, 10, 10));
        RoundButton penBtn   = new RoundButton("icons/pen.svg", 26);
        RoundButton lineBtn  = new RoundButton("icons/line.svg", 26);
        RoundButton rectBtn  = new RoundButton("icons/rect.svg", 26);
        RoundButton ovalBtn  = new RoundButton("icons/kreis.svg", 26);
        RoundButton textBtn  = new RoundButton("icons/text.svg", 26);

        penBtn.addActionListener(e -> { cv.mode = CanvasView.Mode.PEN;   cv.typing=false; cv.requestFocusInWindow(); });
        lineBtn.addActionListener(e -> { cv.mode = CanvasView.Mode.LINE;  cv.typing=false; cv.requestFocusInWindow(); });
        rectBtn.addActionListener(e -> { cv.mode = CanvasView.Mode.RECT;  cv.typing=false; cv.requestFocusInWindow(); });
        ovalBtn.addActionListener(e -> { cv.mode = CanvasView.Mode.OVAL;  cv.typing=false; cv.requestFocusInWindow(); });
        textBtn.addActionListener(e -> { cv.mode = CanvasView.Mode.TEXT;  cv.typing=false; cv.requestFocusInWindow(); });

        p.add(penBtn); p.add(lineBtn); p.add(rectBtn); p.add(ovalBtn); p.add(textBtn);
        p.setSize(300, 120);
        p.setVisible(false);
        return p;
    }

    // Rechts: Text-Optionen (Font, Stil, Größe, HSV → wirkt auf cv.textColor)
    private JPanel buildTextPanel() {
        RoundedPanel p = new RoundedPanel(20);
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6,6,6,6);
        gc.gridx=0; gc.gridy=0; gc.anchor = GridBagConstraints.WEST;

        JLabel lsz = new JLabel("Größe");
        lsz.setForeground(Color.WHITE);
        SpinnerNumberModel fsizeModel = new SpinnerNumberModel(cv.fontSize, 6, 400, 1);
        JSpinner fsizeSpin = new JSpinner(fsizeModel);

        JLabel lth = new JLabel("Hue");
        lth.setForeground(Color.WHITE);
        ShadowSlider sh = new ShadowSlider(0,360, 0);
        JLabel lts = new JLabel("Sat");
        lts.setForeground(Color.WHITE);
        ShadowSlider ss = new ShadowSlider(0,100, 100);
        JLabel ltb = new JLabel("Bri");
        ltb.setForeground(Color.WHITE);
        ShadowSlider sb = new ShadowSlider(0,100, 100);
        JPanel preview = new JPanel(); preview.setPreferredSize(new Dimension(36, 24));
        preview.setBorder(BorderFactory.createLineBorder(new Color(120,120,120)));

        gc.gridx=0; gc.gridy++; gc.weightx=0; gc.fill=GridBagConstraints.NONE; p.add(lsz, gc);
        gc.gridx=1; gc.fill=GridBagConstraints.HORIZONTAL; gc.weightx=1; p.add(fsizeSpin, gc);

        gc.gridx=0; gc.gridy++; gc.weightx=0; p.add(lth, gc);
        gc.gridx=1; gc.weightx=1; gc.fill=GridBagConstraints.HORIZONTAL; p.add(sh, gc);
        gc.gridx=0; gc.gridy++; gc.weightx=0; gc.fill=GridBagConstraints.NONE; p.add(lts, gc);
        gc.gridx=1; gc.weightx=1; gc.fill=GridBagConstraints.HORIZONTAL; p.add(ss, gc);
        gc.gridx=0; gc.gridy++; gc.weightx=0; gc.fill=GridBagConstraints.NONE; p.add(ltb, gc);
        gc.gridx=1; gc.weightx=1; gc.fill=GridBagConstraints.HORIZONTAL; p.add(sb, gc);

        gc.gridx=0; gc.gridy++; gc.gridwidth=2; gc.weightx=0; gc.fill=GridBagConstraints.NONE;
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6,0)); row.setOpaque(false);
        JLabel pvLbl = new JLabel("Vorschau:");
        pvLbl.setForeground(Color.WHITE);
        row.add(pvLbl); row.add(preview);
        p.add(row, gc);

        // logic
        fsizeSpin.addChangeListener(e -> { cv.fontSize = (Integer) fsizeSpin.getValue(); cv.requestFocusInWindow(); cv.repaint(); });

        Runnable apply = () -> {
            float h = sh.getValue()/360f;
            float s = ss.getValue()/100f;
            float b = sb.getValue()/100f;
            cv.textColor = Color.getHSBColor(h,s,b);
            preview.setBackground(cv.textColor);
            cv.requestFocusInWindow();
            cv.repaint();
        };
        sh.addChangeListener(e -> apply.run());
        ss.addChangeListener(e -> apply.run());
        sb.addChangeListener(e -> apply.run());

        // reflect start color
        float[] hsb = Color.RGBtoHSB(cv.textColor.getRed(), cv.textColor.getGreen(), cv.textColor.getBlue(), null);
        sh.setValue(Math.round(hsb[0]*360));
        ss.setValue(Math.round(hsb[1]*100));
        sb.setValue(Math.round(hsb[2]*100));
        preview.setBackground(cv.textColor);

        p.setVisible(false);
        return p;
    }

    // ===== main =====
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new canvasex().setVisible(true));
    }
}
