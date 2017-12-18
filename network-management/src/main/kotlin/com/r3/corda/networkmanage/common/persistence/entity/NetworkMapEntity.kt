package com.r3.corda.networkmanage.common.persistence.entity

import net.corda.nodeapi.internal.crypto.X509CertificateFactory
import net.corda.nodeapi.internal.network.DigitalSignatureWithCert
import javax.persistence.*

@Entity
@Table(name = "network_map")
class NetworkMapEntity(
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE)
        val version: Long? = null,

        @Lob
        @Column(name = "serialized_network_map")
        val networkMap: ByteArray,

        @Lob
        @Column(name = "signature")
        val signature: ByteArray,

        @Lob
        @Column(name = "certificate")
        val certificate: ByteArray
) {
    /**
     * Deserializes NetworkMapEntity.signatureBytes into the [SignatureAndCertPath] instance
     */
    fun signatureAndCertificate(): DigitalSignatureWithCert {
        return DigitalSignatureWithCert(X509CertificateFactory().generateCertificate(certificate.inputStream()), signature)
    }

}