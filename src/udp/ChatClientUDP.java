package udp;


import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.TargetDataLine;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class ChatClientUDP extends JFrame {
    private static final int SERVER_PORT = 5003;
    private static final int MAX_SIZE = 60000;          // limite d'un paquet UDP (images)
    private static final int CHUNK = 8000;              // taille d'un morceau de fichier
    private static final int MAX_FILE = 5 * 1024 * 1024; // fichier max : 5 Mo
    // Voix : 16 kHz, 16 bits, mono (32 Ko par seconde -> environ 2 min max)
    private static final AudioFormat VOICE_FORMAT = new AudioFormat(16000, 16, 1, true, false);

    private final DatagramSocket socket;
    private final InetAddress serverAddress;
    private final String name;

    private final JTextPane chatPane = new JTextPane();
    private final JTextField input = new JTextField();
    private final JComboBox<String> destBox = new JComboBox<>();
    private final JButton voiceBtn = new JButton("Vocal");

    // Fichiers en cours de reception (utilise seulement par le thread listen)
    private final Map<String, Transfer> transfers = new HashMap<>();

    // Enregistrement du micro
    private volatile boolean recording = false;
    private TargetDataLine mic;
    private Thread recorder;
    private ByteArrayOutputStream pcm;

    public ChatClientUDP(String host) throws Exception {
        serverAddress = InetAddress.getByName(host);
        socket = new DatagramSocket();
        socket.setReceiveBufferSize(1024 * 1024); // gros tampon pour les fichiers

        String n = JOptionPane.showInputDialog(null, "Pseudo :");
        if (n == null || n.trim().isEmpty()) System.exit(0);
        name = n.trim();

        // Interface
        setTitle("Chat UDP - " + name);
        setSize(640, 450);
        setLayout(new BorderLayout());

        chatPane.setEditable(false);
        add(new JScrollPane(chatPane), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        destBox.addItem("TOUS");
        bottom.add(destBox, BorderLayout.WEST);
        bottom.add(input, BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        JButton imageBtn = new JButton("Image");
        JButton fileBtn = new JButton("Fichier");
        JButton sendBtn = new JButton("Envoyer");
        buttons.add(imageBtn);
        buttons.add(fileBtn);
        buttons.add(voiceBtn);
        buttons.add(sendBtn);
        bottom.add(buttons, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        sendBtn.addActionListener(e -> sendMessage());
        input.addActionListener(e -> sendMessage());
        imageBtn.addActionListener(e -> sendImage());
        fileBtn.addActionListener(e -> sendFile());
        voiceBtn.addActionListener(e -> toggleVoice());

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                try { send("DISCONNECT", name); } catch (Exception ex) { }
                socket.close();
                System.exit(0);
            }
        });

        setVisible(true);

        send("CONNECT", name);
        new Thread(this::listen).start();
    }

    private void sendMessage() {
        String text = input.getText().trim();
        if (text.isEmpty()) return;
        String dest = (String) destBox.getSelectedItem();
        try {
            send("MSG", name, dest, text);
            if (dest.equals("TOUS")) appendText("Moi -> Tous : " + text + "\n");
            else appendText("Moi -> " + dest + " (prive) : " + text + "\n");
            input.setText("");
        } catch (Exception e) {
            appendText("Erreur d'envoi\n");
        }
    }

    private void sendImage() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        String dest = (String) destBox.getSelectedItem();

        try {
            byte[] data = prepareImage(file);
            if (data == null) {
                JOptionPane.showMessageDialog(this, "Ce fichier n'est pas une image.");
                return;
            }

            // Paquet : IMG, pseudo, destinataire, nom du fichier, taille, octets
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeUTF("IMG");
            out.writeUTF(name);
            out.writeUTF(dest);
            out.writeUTF(file.getName());
            out.writeInt(data.length);
            out.write(data);
            sendBytes(baos.toByteArray());

            if (dest.equals("TOUS")) appendText("Moi -> Tous :\n");
            else appendText("Moi -> " + dest + " (prive) :\n");
            appendImage(data);
        } catch (Exception e) {
            appendText("Erreur d'envoi de l'image\n");
        }
    }

    // Choisit un fichier quelconque (pdf, doc, ...) et l'envoie
    private void sendFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();

        if (file.length() > MAX_FILE) {
            JOptionPane.showMessageDialog(this, "Fichier trop gros (max 5 Mo).");
            return;
        }
        try {
            sendData(file.getName(), Files.readAllBytes(file.toPath()),
                    (String) destBox.getSelectedItem());
        } catch (IOException e) {
            appendText("Erreur de lecture du fichier\n");
        }
    }

    // Envoie des octets decoupes en morceaux de 8 Ko (fichiers et vocaux)
    private void sendData(String filename, byte[] data, String dest) {
        // Dans un thread pour ne pas bloquer la fenetre
        new Thread(() -> {
            try {
                int total = Math.max(1, (data.length + CHUNK - 1) / CHUNK);
                String fileId = name + System.nanoTime();

                for (int i = 0; i < total; i++) {
                    int from = i * CHUNK;
                    int len = Math.min(CHUNK, data.length - from);

                    // Paquet : FILE, pseudo, dest, id, nom, nb morceaux, numero, taille, octets
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    DataOutputStream out = new DataOutputStream(baos);
                    out.writeUTF("FILE");
                    out.writeUTF(name);
                    out.writeUTF(dest);
                    out.writeUTF(fileId);
                    out.writeUTF(filename);
                    out.writeInt(total);
                    out.writeInt(i);
                    out.writeInt(len);
                    out.write(data, from, len);
                    sendBytes(baos.toByteArray());

                    Thread.sleep(2); // petite pause pour ne pas saturer le reseau
                }

                String who = dest.equals("TOUS") ? "Tous" : dest + " (prive)";
                String what = isVoice(filename) ? "vocal envoye" : "fichier envoye : " + filename;
                SwingUtilities.invokeLater(() -> appendText("Moi -> " + who + " : " + what + "\n"));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> appendText("Erreur d'envoi\n"));
            }
        }).start();
    }

    // ---------- Vocal ----------

    private static boolean isVoice(String filename) {
        return filename.startsWith("vocal_") && filename.endsWith(".wav");
    }

    // Un clic = commence a enregistrer, un deuxieme clic = arrete et envoie
    private void toggleVoice() {
        if (!recording) startRecording();
        else stopRecordingAndSend();
    }

    private void startRecording() {
        try {
            mic = AudioSystem.getTargetDataLine(VOICE_FORMAT);
            mic.open(VOICE_FORMAT);
            mic.start();
        } catch (Exception e) {
            appendText("Micro introuvable ou bloque\n");
            return;
        }

        pcm = new ByteArrayOutputStream();
        recording = true;
        voiceBtn.setText("Stop");
        appendText("Enregistrement... clique sur Stop pour envoyer\n");

        recorder = new Thread(() -> {
            byte[] buf = new byte[4096];
            while (recording && pcm.size() < MAX_FILE - 1000) {
                int n = mic.read(buf, 0, buf.length);
                if (n > 0) pcm.write(buf, 0, n);
            }
        });
        recorder.start();
    }

    private void stopRecordingAndSend() {
        recording = false;
        voiceBtn.setText("Vocal");
        try { recorder.join(); } catch (InterruptedException e) { }
        mic.stop();
        mic.close();

        byte[] raw = pcm.toByteArray();
        if (raw.length < 3200) { // moins de 0,1 seconde
            appendText("Vocal trop court, rien envoye\n");
            return;
        }
        try {
            sendData("vocal_" + System.currentTimeMillis() + ".wav", toWav(raw),
                    (String) destBox.getSelectedItem());
        } catch (IOException e) {
            appendText("Erreur de creation du vocal\n");
        }
    }

    // Transforme les octets bruts du micro en fichier WAV
    static byte[] toWav(byte[] raw) throws IOException {
        AudioInputStream ais = new AudioInputStream(
                new ByteArrayInputStream(raw), VOICE_FORMAT, raw.length / VOICE_FORMAT.getFrameSize());
        ByteArrayOutputStream wav = new ByteArrayOutputStream();
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wav);
        return wav.toByteArray();
    }

    private void playAudio(byte[] data) {
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(data));
            Clip clip = AudioSystem.getClip();
            clip.open(ais);
            clip.start();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Impossible de lire le vocal.");
        }
    }

    // ---------- Images ----------

    // Reduit l'image (largeur max 400, JPEG) jusqu'a ce qu'elle tienne dans un paquet UDP
    static byte[] prepareImage(File file) throws IOException {
        BufferedImage img = ImageIO.read(file);
        if (img == null) return null;

        int w = Math.min(img.getWidth(), 400);
        while (true) {
            int h = Math.max(1, img.getHeight() * w / img.getWidth());
            BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(img, 0, 0, w, h, null);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(rgb, "jpg", baos);
            if (baos.size() <= MAX_SIZE || w <= 50) return baos.toByteArray();
            w = w * 3 / 4;
        }
    }

    // ---------- Reception ----------

    // Thread qui recoit les paquets du serveur
    private void listen() {
        byte[] buffer = new byte[65507];
        try {
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                DataInputStream in = new DataInputStream(
                        new ByteArrayInputStream(packet.getData(), 0, packet.getLength()));
                String type = in.readUTF();

                if (type.equals("MSG")) {
                    String text = in.readUTF();
                    SwingUtilities.invokeLater(() -> appendText(text + "\n"));
                } else if (type.equals("LISTE")) {
                    String list = in.readUTF();
                    SwingUtilities.invokeLater(() -> updateUsers(list));
                } else if (type.equals("ERREUR")) {
                    String text = in.readUTF();
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this, text);
                        if (text.startsWith("Pseudo")) System.exit(0);
                    });
                } else if (type.equals("IMG")) {
                    String sender = in.readUTF();
                    in.readUTF(); // nom du fichier (non utilise)
                    byte[] data = new byte[in.readInt()];
                    in.readFully(data);
                    SwingUtilities.invokeLater(() -> {
                        appendText("[" + sender + "] image :\n");
                        appendImage(data);
                    });
                } else if (type.equals("FILE")) {
                    String sender = in.readUTF();
                    String fileId = in.readUTF();
                    String filename = in.readUTF();
                    int total = in.readInt();
                    int index = in.readInt();
                    byte[] data = new byte[in.readInt()];
                    in.readFully(data);

                    // Rassemble les morceaux ; quand tout est la, on affiche le fichier
                    String key = sender + fileId;
                    Transfer t = transfers.computeIfAbsent(key, k -> new Transfer(total));
                    if (t.add(index, data)) {
                        transfers.remove(key);
                        byte[] all = t.join();
                        SwingUtilities.invokeLater(() -> {
                            if (isVoice(filename)) appendVoice(sender, all);
                            else appendFile(sender, filename, all);
                        });
                    }
                }
            }
        } catch (Exception e) {
            // socket fermee
        }
    }

    // Morceaux d'un fichier en cours de reception
    static class Transfer {
        final byte[][] parts;
        int received = 0;

        Transfer(int total) { parts = new byte[total][]; }

        // Retourne true quand tous les morceaux sont arrives
        boolean add(int index, byte[] data) {
            if (index >= 0 && index < parts.length && parts[index] == null) {
                parts[index] = data;
                received++;
            }
            return received == parts.length;
        }

        byte[] join() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] p : parts) out.write(p, 0, p.length);
            return out.toByteArray();
        }
    }

    // ---------- Affichage ----------

    private void appendText(String text) {
        try {
            StyledDocument doc = chatPane.getStyledDocument();
            doc.insertString(doc.getLength(), text, null);
            chatPane.setCaretPosition(doc.getLength());
        } catch (BadLocationException e) { }
    }

    private void appendImage(byte[] data) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            chatPane.setCaretPosition(chatPane.getDocument().getLength());
            chatPane.insertIcon(new ImageIcon(img));
            appendText("\n");
        } catch (Exception e) {
            appendText("(image illisible)\n");
        }
    }

    // Fichier recu : nom + boutons Ouvrir / Telecharger
    private void appendFile(String sender, String filename, byte[] data) {
        String shortName = new File(filename).getName(); // evite les chemins dans le nom
        appendText("[" + sender + "] fichier : " + shortName
                + " (" + Math.max(1, data.length / 1024) + " Ko)\n");

        JButton openBtn = new JButton("Ouvrir");
        JButton saveBtn = new JButton("Telecharger");
        openBtn.addActionListener(e -> openFile(shortName, data));
        saveBtn.addActionListener(e -> saveFile(shortName, data));
        insertButtons(openBtn, saveBtn);
    }

    // Vocal recu : duree + boutons Ecouter / Telecharger
    private void appendVoice(String sender, byte[] data) {
        int seconds = Math.max(1, (data.length - 44) / 32000); // 44 = entete WAV
        appendText("[" + sender + "] message vocal (" + seconds + " s)\n");

        JButton playBtn = new JButton("Ecouter");
        JButton saveBtn = new JButton("Telecharger");
        playBtn.addActionListener(e -> playAudio(data));
        saveBtn.addActionListener(e -> saveFile("vocal.wav", data));
        insertButtons(playBtn, saveBtn);
    }

    private void insertButtons(JButton a, JButton b) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.add(a);
        panel.add(b);
        chatPane.setCaretPosition(chatPane.getDocument().getLength());
        chatPane.insertComponent(panel);
        appendText("\n");
    }

    // Ouvrir : ecrit le fichier dans le dossier temporaire puis l'ouvre
    private void openFile(String filename, byte[] data) {
        try {
            File dir = new File(System.getProperty("java.io.tmpdir"), "chat_udp_" + name);
            dir.mkdirs();
            File f = new File(dir, filename);
            Files.write(f.toPath(), data);
            Desktop.getDesktop().open(f);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Impossible d'ouvrir le fichier.");
        }
    }

    // Telecharger : l'utilisateur choisit ou l'enregistrer
    private void saveFile(String filename, byte[] data) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(filename));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.write(chooser.getSelectedFile().toPath(), data);
            JOptionPane.showMessageDialog(this, "Fichier enregistre.");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Erreur d'enregistrement.");
        }
    }

    // Met a jour la liste deroulante (TOUS + les autres utilisateurs)
    private void updateUsers(String list) {
        Object selected = destBox.getSelectedItem();
        destBox.removeAllItems();
        destBox.addItem("TOUS");
        for (String u : list.split(",")) {
            if (!u.isEmpty() && !u.equals(name)) destBox.addItem(u);
        }
        if (selected != null) destBox.setSelectedItem(selected);
    }

    // Envoie un paquet : type + champs texte
    private void send(String type, String... fields) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeUTF(type);
        for (String f : fields) out.writeUTF(f);
        sendBytes(baos.toByteArray());
    }

    private void sendBytes(byte[] data) throws IOException {
        socket.send(new DatagramPacket(data, data.length, serverAddress, SERVER_PORT));
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        SwingUtilities.invokeLater(() -> {
            try { new ChatClientUDP(host); } catch (Exception e) { e.printStackTrace(); }
        });
    }
}