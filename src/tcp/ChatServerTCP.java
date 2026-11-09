package tcp;


import java.io.*;
import java.net.*;
import java.util.*;

public class ChatServerTCP {
    private static final int PORT = 5003;
    // pseudo -> flux de sortie du client
    private static final Map<String, DataOutputStream> clients = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        ServerSocket server = new ServerSocket(PORT);
        System.out.println("Serveur TCP demarre sur le port " + PORT);

        while (true) {
            Socket s = server.accept();
            new Thread(() -> handleClient(s)).start(); // un thread par client
        }
    }

    // Lit les messages d'un client : chaque message = taille (int) + octets
    private static void handleClient(Socket s) {
        String name = null;
        DataOutputStream sender = null;
        try {
            DataInputStream sock = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            sender = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));

            while (true) {
                byte[] frame = new byte[sock.readInt()];
                sock.readFully(frame);
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
                try {
                    String type = in.readUTF();
                    // ponytail: un seul verrou global, un client lent ralentit les autres
                    synchronized (clients) {
                        switch (type) {
                            case "CONNECT": {
                                String n = in.readUTF();
                                if (handleConnect(n, sender, s)) name = n;
                                break;
                            }
                            case "MSG":        handleMessage(in, sender); break;
                            case "IMG":        handleImage(in, sender); break;
                            case "FILE":       handleFile(in, sender); break;
                            case "DISCONNECT": handleDisconnect(in.readUTF(), sender); break;
                        }
                    }
                } catch (IOException e) {
                    System.out.println("Message invalide ignore : " + e.getMessage());
                }
            }
        } catch (IOException e) {
            // connexion fermee (ou coupee sans DISCONNECT)
        }
        if (name != null) {
            synchronized (clients) { handleDisconnect(name, sender); }
        }
        try { s.close(); } catch (IOException e) { }
    }

    private static boolean handleConnect(String name, DataOutputStream out, Socket s) {
        if (clients.containsKey(name) && clients.get(name) != out) {
            sendText(out, "ERREUR", "Pseudo deja utilise");
            return false;
        }
        clients.put(name, out);
        System.out.println(name + " connecte depuis " + s.getRemoteSocketAddress());
        broadcast("** " + name + " a rejoint le chat **", name);
        sendList();
        return true;
    }

    private static void handleMessage(DataInputStream in, DataOutputStream out) throws IOException {
        String name = in.readUTF();
        String dest = in.readUTF();
        String text = in.readUTF();

        if (dest.equalsIgnoreCase("TOUS")) {
            broadcast("[" + name + "] " + text, name);
        } else if (clients.containsKey(dest)) {
            sendText(clients.get(dest), "MSG", "(prive de " + name + ") " + text);
        } else {
            sendText(out, "ERREUR", "Utilisateur '" + dest + "' introuvable");
        }
    }

    private static void handleImage(DataInputStream in, DataOutputStream from) throws IOException {
        String name = in.readUTF();
        String dest = in.readUTF();
        String filename = in.readUTF();
        byte[] data = new byte[in.readInt()];
        in.readFully(data);

        // Message a renvoyer : IMG, expediteur, nom du fichier, taille, octets
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeUTF("IMG");
        out.writeUTF(name);
        out.writeUTF(filename);
        out.writeInt(data.length);
        out.write(data);
        route(name, dest, baos.toByteArray(), from, true);
    }

    // Un fichier arrive en plusieurs morceaux (chunks) : chacun est renvoye tel quel
    private static void handleFile(DataInputStream in, DataOutputStream from) throws IOException {
        String name = in.readUTF();
        String dest = in.readUTF();
        String fileId = in.readUTF();
        String filename = in.readUTF();
        int total = in.readInt();
        int index = in.readInt();
        byte[] data = new byte[in.readInt()];
        in.readFully(data);

        // Message a renvoyer : FILE, expediteur, id, nom, nb morceaux, numero, taille, octets
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeUTF("FILE");
        out.writeUTF(name);
        out.writeUTF(fileId);
        out.writeUTF(filename);
        out.writeInt(total);
        out.writeInt(index);
        out.writeInt(data.length);
        out.write(data);
        // l'erreur "introuvable" n'est envoyee qu'une fois (au premier morceau)
        route(name, dest, baos.toByteArray(), from, index == 0);
    }

    // Envoie un message deja pret a tous (sauf l'expediteur) ou a un seul client
    private static void route(String name, String dest, byte[] packet,
                              DataOutputStream from, boolean reportError) {
        if (dest.equalsIgnoreCase("TOUS")) {
            for (Map.Entry<String, DataOutputStream> e : clients.entrySet()) {
                if (!e.getKey().equals(name)) sendBytes(e.getValue(), packet);
            }
        } else if (clients.containsKey(dest)) {
            sendBytes(clients.get(dest), packet);
        } else if (reportError) {
            sendText(from, "ERREUR", "Utilisateur '" + dest + "' introuvable");
        }
    }

    // Retire le client seulement si ce pseudo est bien le sien
    private static void handleDisconnect(String name, DataOutputStream out) {
        if (clients.remove(name, out)) {
            System.out.println(name + " deconnecte");
            broadcast("** " + name + " a quitte le chat **", name);
            sendList();
        }
    }

    // Envoie un message texte a tous les clients sauf 'exclude'
    private static void broadcast(String msg, String exclude) {
        for (Map.Entry<String, DataOutputStream> e : clients.entrySet()) {
            if (!e.getKey().equals(exclude)) sendText(e.getValue(), "MSG", msg);
        }
    }

    // Envoie la liste des connectes a tout le monde
    private static void sendList() {
        String list = String.join(",", clients.keySet());
        for (DataOutputStream out : clients.values()) sendText(out, "LISTE", list);
    }

    private static void sendText(DataOutputStream to, String type, String text) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeUTF(type);
            out.writeUTF(text);
            sendBytes(to, baos.toByteArray());
        } catch (IOException e) { }
    }

    // Ecrit un message : taille + octets. Une erreur ici = client parti, son thread le retirera
    private static void sendBytes(DataOutputStream to, byte[] data) {
        try {
            to.writeInt(data.length);
            to.write(data);
            to.flush();
        } catch (IOException e) { }
    }
}
