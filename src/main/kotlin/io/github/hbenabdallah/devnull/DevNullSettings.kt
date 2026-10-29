package io.github.hbenabdallah.devnull

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.panel

@State(name = "DevNullSettings", storages = [Storage("devnull.xml")])
class DevNullSettings : PersistentStateComponent<DevNullSettings.State> {
    class State {
        var url: String = "https://dev-null.sh"
        var linkToGitHub: Boolean = true

        /** The site and handle the stored key signed in to. */
        var accountUrl: String? = null
        var handle: String? = null
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    val url: String get() = state.url.trimEnd('/')

    /**
     * The key belongs to one site, so changing the site asks for a new sign-in.
     */
    fun client(): DevNullClient? {
        val encoded = PasswordSafe.instance.getPassword(keyAttributes) ?: return null

        return if (state.accountUrl == url) DevNullClient(url, DeviceKey.decode(encoded)) else null
    }

    fun signedIn(key: DeviceKey, author: Author) {
        PasswordSafe.instance.set(keyAttributes, Credentials("device", key.encode()))
        state.accountUrl = url
        state.handle = author.handle
    }

    fun forget(): String? {
        val handle = state.handle
        PasswordSafe.instance.set(keyAttributes, null)
        state.accountUrl = null
        state.handle = null

        return handle
    }

    companion object {
        private val keyAttributes = CredentialAttributes(generateServiceName("/dev/null", "device key"))

        fun getInstance(): DevNullSettings = ApplicationManager.getApplication().getService(DevNullSettings::class.java)
    }
}

class DevNullConfigurable : BoundConfigurable("/dev/null") {
    override fun createPanel(): DialogPanel {
        val state = DevNullSettings.getInstance().state

        return panel {
            row("Site:") {
                textField().bindText(state::url).columns(COLUMNS_LARGE).comment("The /dev/null site to commit to.")
            }
            row {
                checkBox("Link to the selected lines on GitHub once they are pushed").bindSelected(state::linkToGitHub)
            }
            row {
                comment(state.handle?.let { "Signed in as @$it." } ?: "Not signed in. Use Tools → /dev/null → Sign In.")
            }
        }
    }
}
