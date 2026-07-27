package dev.nulli0n.vbot.verify;

import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;

/**
 * Build-time smoke entry point. It is intentionally run from the shaded JAR so
 * relocation or missing runtime dependencies fail the build before deployment.
 */
public final class ProtocolSmokeMain {
    private ProtocolSmokeMain() {
    }

    public static void main(String[] args) {
        MinecraftProtocol protocol = new MinecraftProtocol("VBotSmoke");
        ClientSession session = ClientNetworkSessionFactory.factory()
            .setAddress("127.0.0.1", 1)
            .setProtocol(protocol)
            .create();
        int protocolVersion = protocol.getCodec().getProtocolVersion();
        if (protocolVersion <= 0 || session.isConnected()) {
            throw new IllegalStateException("Embedded protocol client smoke test failed");
        }
        System.out.println("Embedded Minecraft protocol version: " + protocolVersion);
    }
}
