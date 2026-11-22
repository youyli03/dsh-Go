# Android remote control / Android 远控

The **Remote control** mode connects to an SSH server and requests a reverse forward bound to server-side `127.0.0.1`. The Android foreground service holds the connection after switching modes, going to the home screen, or locking. To stop it, use the mode page or the notification. Auto-start starts the connection when the App opens, not when Android boots, and does not restore a temporary permission grant.

「远控」模式通过 SSH 反向转发，在服务器端请求监听 `127.0.0.1`。切换模式、退到桌面或锁屏后，连接由 Android 前台服务维持。可从页面或通知停止。自启动只在打开 App 时连 SSH，不是开机启动，也不会恢复限时授权。

## Server / 服务器

- Use `GatewayPorts no` on the SSH server so it cannot turn the requested loopback listener into a wildcard listener. Check the effective SSH server configuration and confirm the remote listener is only on loopback before granting access. A separate SSH account restricted to this forwarding use is recommended.
- Copy the pinned SSH host fingerprint from a trusted server console. Do not infer it from the untrusted network connection.
- Configure your MCP client on the server with the loopback URL shown by the App and `Authorization: Bearer <token>`. Do not put the token in the URL, repository or shared configuration. If the client runs on another machine, use a separate local SSH forward instead of exposing the MCP port publicly.

- 服务器应使用 `GatewayPorts no`，防止回环监听被覆盖为全接口监听。确认 SSH 服务的最终配置以及监听地址后再授权。建议使用仅允许该转发用途的独立账户。
- SSH 主机指纹从可信的服务器控制台获取，不以未经验证的网络连接所报指纹为准。
- 在服务器上的 MCP 客户端使用 App 显示的本地地址，并配置 `Authorization: Bearer <token>`；不要把令牌放入 URL、仓库或共享配置。客户端如果在另一台机器上，再使用 SSH 本地转发，不公开 MCP 端口。

The tunnel staying connected does not grant phone access. Locking denies phone tools; unlock requests must be issued anew. Before deploying, verify notification stop, token rotation, lock/unlock, and network change on the actual target phone.

隧道在线不代表已授权。锁屏拒绝手机工具，解锁后需重新发起调用。使用前在目标真机验证通知停止、令牌轮换、锁屏解锁及网络切换。
