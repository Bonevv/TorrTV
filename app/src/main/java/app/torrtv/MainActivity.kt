package app.torrtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.torrtv.state.Graph
import app.torrtv.ui.App
import app.torrtv.ui.LoginScreen
import app.torrtv.ui.TorrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TorrTheme { if (Graph.sync.loggedIn) App() else LoginScreen() } }
    }
}
