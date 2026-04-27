package bettermotd;

public record RequestInfo(String ip, String hostname, int protocolVersion, int onlinePlayers, int maxPlayers) {
    public static RequestInfo preview(String ip, int onlinePlayers, int maxPlayers) {
        return new RequestInfo(ip, "", -1, onlinePlayers, maxPlayers);
    }
}
