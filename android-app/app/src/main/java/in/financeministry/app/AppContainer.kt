package `in`.financeministry.app

import android.app.Application

class AppContainer(
    val application: Application,
) {
    val repository by lazy { `in`.financeministry.app.data.TransactionRepository(application) }
}
