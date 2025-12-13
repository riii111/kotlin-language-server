interface TokenIssuer {
    fun generateToken(): String
}

class TokenIssuerImpl : TokenIssuer {
    override fun generateToken(): String = "token"
}

class FakeTokenIssuer : TokenIssuer {
    override fun generateToken(): String = "fake-token"
}

object InterfaceReferenceCaller {
    fun callViaInterface(issuer: TokenIssuer) {
        issuer.generateToken()
    }

    fun callDirectly() {
        val impl = TokenIssuerImpl()
        impl.generateToken()
    }
}
