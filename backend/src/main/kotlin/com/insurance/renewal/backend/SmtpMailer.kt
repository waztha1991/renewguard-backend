package com.insurance.renewal.backend

import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

data class SmtpConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val fromEmail: String,
    val fromName: String,
    val useTls: Boolean
) {
    fun isComplete(): Boolean =
        host.isNotBlank() &&
            port in 1..65535 &&
            fromEmail.isNotBlank() &&
            (username.isBlank() || password.isNotBlank())
}

class SmtpMailer {
    @Volatile
    private var config: SmtpConfig? = null

    fun configure(config: SmtpConfig?) {
        this.config = config?.takeIf { it.isComplete() }
    }

    fun isConfigured(): Boolean = config != null

    fun currentConfig(): SmtpConfig? = config

    fun send(to: String, subject: String, bodyText: String) {
        val cfg = config ?: error("SMTP is not configured. Set it under Admin → Settings.")
        require(to.isNotBlank()) { "Recipient email is required" }

        val props = Properties().apply {
            put("mail.smtp.host", cfg.host)
            put("mail.smtp.port", cfg.port.toString())
            put("mail.smtp.auth", (cfg.username.isNotBlank()).toString())
            if (cfg.useTls) {
                if (cfg.port == 465) {
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.socketFactory.port", cfg.port.toString())
                    put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                } else {
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.starttls.required", "true")
                }
            }
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "15000")
        }

        val session = if (cfg.username.isNotBlank()) {
            Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(cfg.username, cfg.password)
            })
        } else {
            Session.getInstance(props)
        }

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(cfg.fromEmail, cfg.fromName.ifBlank { "RenewGuard" }))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to.trim()))
            this.subject = subject
            setText(bodyText)
        }
        Transport.send(message)
    }

    fun sendPasswordResetEmail(
        to: String,
        agentName: String,
        temporaryPassword: String
    ) {
        val name = agentName.ifBlank { "Agent" }
        val subject = "RenewGuard — temporary password"
        val body = """
            Hello $name,

            An administrator at AntSolutions / RenewGuard has set a temporary password for your account.

            Temporary password: $temporaryPassword

            Sign in with this password, then change it immediately under Settings → Change password.

            If you did not request this, contact your administrator.

            — RenewGuard by AntSolutions
        """.trimIndent()
        send(to, subject, body)
    }

    fun sendTestEmail(to: String) {
        send(
            to = to,
            subject = "RenewGuard SMTP test",
            bodyText = """
                This is a test email from RenewGuard (AntSolutions).

                If you received this, SMTP is configured correctly.
            """.trimIndent()
        )
    }
}
