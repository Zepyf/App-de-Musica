package com.example.zepyfyv2

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.example.zepyfyv2.auth.GoogleAuth
import com.example.zepyfyv2.databinding.ActivityLoginBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Pantalla de inicio de sesión. MainActivity la abre si no hay sesión de Firebase;
 * tras iniciar sesión vuelve a MainActivity y esta pantalla se cierra.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (GoogleAuth.currentUser != null) {
            openMain()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Si el json aún no permite Google Sign-In, explicarlo en vez de fallar al pulsar
        if (GoogleAuth.webClientId(this) == null) {
            binding.signInButton.isEnabled = false
            showError(getString(R.string.login_error_missing_config))
            return
        }

        binding.signInButton.setOnClickListener { signIn() }
    }

    private fun signIn() {
        setLoading(true)
        lifecycleScope.launch {
            try {
                GoogleAuth.signIn(this@LoginActivity)
                openMain()
            } catch (e: CancellationException) {
                throw e
            } catch (e: GetCredentialCancellationException) {
                setLoading(false) // el usuario cerró el selector: no es un error
            } catch (e: NoCredentialException) {
                setLoading(false)
                showError(getString(R.string.login_error_no_accounts))
            } catch (e: GoogleAuth.MissingConfigException) {
                setLoading(false)
                showError(getString(R.string.login_error_missing_config))
            } catch (e: GetCredentialException) {
                Log.e(TAG, "Error de Credential Manager", e)
                setLoading(false)
                showError(getString(R.string.login_error_generic, e.message ?: e.type))
            } catch (e: Exception) {
                Log.e(TAG, "Error iniciando sesión", e)
                setLoading(false)
                showError(getString(R.string.login_error_generic, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.signInButton.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) binding.errorTextView.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.errorTextView.text = message
        binding.errorTextView.visibility = View.VISIBLE
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private const val TAG = "LoginActivity"
    }
}
