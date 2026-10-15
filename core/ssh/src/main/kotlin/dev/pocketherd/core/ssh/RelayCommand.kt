package dev.pocketherd.core.ssh

/**
 * The remote command that bridges an SSH exec channel to herdr's Unix socket.
 *
 * Why a relay command at all: herdr listens on a Unix domain socket with no auth token and
 * no TCP listener, so the only way in from a phone is SSH. SSHJ does not implement
 * `direct-streamlocal@openssh.com` (there is no such channel class in the library), so
 * `ssh -L` style Unix socket forwarding is unavailable. An exec channel running a pipe is
 * what is left, and it has the advantage of needing nothing installed on the host.
 *
 * Each candidate copies stdin into the socket and the socket back to stdout, unbuffered.
 * They are tried in order and the first that returns a valid `pong` is remembered per host.
 */
object RelayCommand {

    /**
     * python3 is the first choice: present on effectively every Linux and macOS dev box, and
     * unlike `nc` its `-U` behaviour does not vary between BSD and GNU builds.
     *
     * The script avoids single quotes entirely so the whole thing can be wrapped in them for
     * the remote shell. `os._exit` skips interpreter teardown so a closed socket ends the
     * process immediately instead of waiting on the reader thread.
     */
    private val PYTHON3 = """
        python3 -c "
        import os,socket,sys,threading
        s=socket.socket(socket.AF_UNIX)
        s.connect(os.environ.get(\"HERDR_SOCKET_PATH\") or os.path.expanduser(\"%SOCKET%\"))
        def pump():
            for d in iter(lambda: s.recv(65536), b\"\"):
                sys.stdout.buffer.write(d); sys.stdout.buffer.flush()
            os._exit(0)
        t=threading.Thread(target=pump); t.start()
        for line in sys.stdin.buffer: s.sendall(line)
        s.shutdown(socket.SHUT_WR); t.join()
        "
    """.trimIndent()

    /** socat is the cleanest when present, but is not installed by default anywhere. */
    private const val SOCAT = "socat - UNIX-CONNECT:%SOCKET%"

    /**
     * Last resort. BSD netcat on macOS and netcat-openbsd both support -U; the GNU
     * netcat traditional build does not, which is why this is not tried first.
     */
    private const val NETCAT = "nc -U %SOCKET%"

    const val DEFAULT_SOCKET = "~/.config/herdr/herdr.sock"

    /** In preference order. The probe walks this list once per machine and caches the winner. */
    val CANDIDATES: List<Relay> = listOf(
        Relay("python3", PYTHON3),
        Relay("socat", SOCAT),
        Relay("nc", NETCAT),
    )

    fun byName(name: String): Relay? = CANDIDATES.firstOrNull { it.name == name }

    data class Relay(val name: String, private val template: String) {
        /**
         * Resolves the socket path into the command.
         *
         * A named herdr session lives at `~/.config/herdr/sessions/<name>/herdr.sock`, so the
         * path is caller-supplied rather than hardcoded. Tilde expansion is left to the
         * remote shell for socat and nc, and done by Python for the python3 relay.
         */
        fun render(socketPath: String = DEFAULT_SOCKET): String =
            template.replace("%SOCKET%", socketPath)
    }
}
