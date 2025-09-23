import javax.swing.*;

public class TitelTest extends JFrame {

    public TitelTest() {
        // Titel setzen
        setTitle("Freeplay");

        setSize(300, 200);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setVisible(true);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(TitelTest::new);
    }
}

