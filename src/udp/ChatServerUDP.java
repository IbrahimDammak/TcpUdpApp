package udp;


import java.io.*;
import java.net.*;
import java.util.*;

public class ChatServerUDP {
    private static final int PORT = 5003;
    // pseudo -> adresse (IP + port) du client
    private static final Map<String, InetSocketAddress> clients = new LinkedHashMap<>();
    private static DatagramSocket socket;

    public static void main(String[] args) throws Exception {
        socket = new DatagramSocket(PORT);
        socket.setReceiveBufferSize(1024 * 1024); // gros tampon pour les fichiers
        System.out.println("Serveur UDP demarre sur le port " + PORT);

        byte[] buffer = new byte[65507];
        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            InetSocketAddress sender = new InetSocketAddress(packet.getAddress(), packet.getPort());
            DataInputStream in = new DataInputStream(
                    new ByteArrayInputStream(packet.getData(), 0, packet.getLength()));
            try {
                String type = in.readUTF();
                switch (type) {
                    case "CONNECT":    handleConnect(in.readUTF(), sender); break;
                    case "MSG":        handleMessage(in, sender); break;
                    case "IMG":        handleImage(in, sender); break;
                    case "FILE":       handleFile(in, sender); break;
                    case "DISCONNECT": handleDisconnect(in.readUTF()); break;
                }
            } catch (IOException e) {
                System.out.println("Paquet invalide ignore : " + e.getMessage());
            }
        }
    }

    private static void handleConnect(String name, InetSocketAddress addr) throws IOException {
        if (clients.containsKey(name) && !clients.get(name).equals(addr)) {
            sendText(addr, "ERREUR", "Pseudo deja utilise");
            return;
        }
        clients.put(name, addr);
        System.out.println(name + " connecte depuis " + addr);
        broadcast("** " + name + " a rejoint le chat **", name);
        sendList();
    }

    private static void handleMessage(DataInputStream in, InetSocketAddress addr) throws IOException {
        String name = in.readUTF();
        String dest = in.readUTF();
        String text = in.readUTF();

        if (dest.equalsIgnoreCase("TOUS")) {
            broadcast("[" + name + "] " + text, name);
        } else if (clients.containsKey(dest)) {
            sendText(clients.get(dest), "MSG", "(prive de " + name + ") " + text);
        } else {
            sendText(addr, "ERREUR", "Utilisateur '" + dest + "' introuvable");
        }
    }

    private static void handleImage(DataInputStream in, InetSocketAddress addr) throws IOException {
        String name = in.readUTF();
        String dest = in.readUTF();
        String filename = in.readUTF();
        byte[] data = new byte[in.readInt()];
        in.readFully(data);

        // Paquet a renvoyer : IMG, expediteur, nom du fichier, taille, octets
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeUTF("IMG");
        out.writeUTF(name);
        out.writeUTF(filename);
        out.writeInt(data.length);
        out.write(data);
        route(name, dest, baos.toByteArray(), addr, true);
    }

    // Un fichier arrive en plusieurs morceaux (chunks) : chacun est renvoye tel quel
    private static void handleFile(DataInputStream in, InetSocketAddress addr) throws IOException {
        String name = in.readUTF();
        String dest = in.readUTF();
        String fileId = in.readUTF();
        String filename = in.readUTF();
        int total = in.readInt();
        int index = in.readInt();
        byte[] data = new byte[in.readInt()];
        in.readFully(data);

        // Paquet a renvoyer : FILE, expediteur, id, nom, nb morceaux, numero, taille, octets
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
        route(name, dest, baos.toByteArray(), addr, index == 0);
    }

    // Envoie un paquet deja pret a tous (sauf l'expediteur) ou a un seul client
    private static void route(String name, String dest, byte[] packet,
                              InetSocketAddress addr, boolean reportError) throws IOException {
        if (dest.equalsIgnoreCase("TOUS")) {
            for (Map.Entry<String, InetSocketAddress> e : clients.entrySet()) {
                if (!e.getKey().equals(name)) sendBytes(e.getValue(), packet);
            }
        } else if (clients.containsKey(dest)) {
            sendBytes(clients.get(dest), packet);
        } else if (reportError) {
            sendText(addr, "ERREUR", "Utilisateur '" + dest + "' introuvable");
        }
    }

    private static void handleDisconnect(String name) throws IOException {
        if (clients.remove(name) != null) {
            System.out.println(name + " deconnecte");
            broadcast("** " + name + " a quitte le chat **", name);
            sendList();
        }
    }

    // Envoie un message texte a tous les clients sauf 'exclude'
    private static void broadcast(String msg, String exclude) throws IOException {
        for (Map.Entry<String, InetSocketAddress> e : clients.entrySet()) {
            if (!e.getKey().equals(exclude)) sendText(e.getValue(), "MSG", msg);
        }
    }

    // Envoie la liste des connectes a tout le monde
    private static void sendList() throws IOException {
        String list = String.join(",", clients.keySet());
        for (InetSocketAddress addr : clients.values()) sendText(addr, "LISTE", list);
    }

    private static void sendText(InetSocketAddress addr, String type, String text) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeUTF(type);
        out.writeUTF(text);
        sendBytes(addr, baos.toByteArray());
    }

    private static void sendBytes(InetSocketAddress addr, byte[] data) throws IOException {
        socket.send(new DatagramPacket(data, data.length, addr.getAddress(), addr.getPort()));
    }
}