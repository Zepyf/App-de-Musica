package com.example.zepyfyv2.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Inicio de sesión con Google + Firebase Auth usando Credential Manager.
 *
 * Flujo: Credential Manager muestra el selector de cuentas de Google -> devuelve un
 * ID token -> se cambia por una sesión de Firebase. Firebase guarda la sesión, así que
 * solo hay que iniciar sesión una vez por dispositivo.
 *
 * Iniciar sesión NO da acceso a los datos por sí solo: cualquiera con cuenta de Google
 * puede autenticarse en tu proyecto. Lo que limita quién lee y escribe son las reglas
 * (firebase/firestore.rules y firebase/storage.rules), que solo admiten los UID listados.
 */
object GoogleAuth {

    private const val TAG = "GoogleAuth"

    /** La configuración de Firebase todavía no permite iniciar sesión con Google. */
    class MissingConfigException : Exception(
        "Falta default_web_client_id en google-services.json"
    )

    val currentUser: FirebaseUser?
        get() = FirebaseAuth.getInstance().currentUser

    /**
     * ID de cliente web de OAuth. Lo genera el plugin google-services a partir del
     * `oauth_client` de tipo 3 de google-services.json, que solo aparece después de
     * activar Google en Firebase Authentication y añadir la huella SHA-1 de la app.
     *
     * Se busca por nombre (y no con R.string.default_web_client_id) para que el proyecto
     * compile aunque el json aún no esté configurado: en ese caso la pantalla de inicio
     * de sesión explica qué falta en lugar de fallar la compilación.
     */
    @SuppressLint("DiscouragedApi")
    fun webClientId(context: Context): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id != 0) context.getString(id) else null
    }

    /**
     * Muestra el selector de cuentas y abre sesión en Firebase.
     * Lanza las excepciones de Credential Manager (p. ej. si el usuario cancela)
     * o [MissingConfigException] si falta configurar Google en Firebase.
     */
    suspend fun signIn(activity: Activity): FirebaseUser {
        val clientId = webClientId(activity) ?: throw MissingConfigException()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential

        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw IllegalStateException("Tipo de credencial inesperado: ${credential.type}")
        }

        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
        val result = FirebaseAuth.getInstance().signInWithCredential(firebaseCredential).await()
        return result.user ?: throw IllegalStateException("Firebase no devolvió usuario")
    }

    /** Cierra la sesión de Firebase y olvida la cuenta elegida para que la próxima vez pregunte. */
    suspend fun signOut(context: Context) {
        FirebaseAuth.getInstance().signOut()
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo limpiar el estado de Credential Manager", e)
        }
    }
}
