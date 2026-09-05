package `in`.financeministry.app

import android.app.Application

class FinanceMinistryApp : Application() {
    val container by lazy { AppContainer(this) }
}
