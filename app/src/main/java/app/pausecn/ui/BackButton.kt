package app.pausecn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BackButton(onClick: () -> Unit, label: String = "返回", enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Sage),
        colors = ButtonDefaults.buttonColors(containerColor = SageSoft, contentColor = Ink)) {
        Text("←", fontSize = 22.sp)
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}
