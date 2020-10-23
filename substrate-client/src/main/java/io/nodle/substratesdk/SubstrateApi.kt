package io.nodle.substratesdk

import io.nodle.substratesdk.account.Account
import io.nodle.substratesdk.account.Wallet
import io.nodle.substratesdk.account.signMsg
import io.nodle.substratesdk.rpc.AuthorSubmitExtrinsic
import io.nodle.substratesdk.rpc.PaymentQueryInfo
import io.nodle.substratesdk.rpc.StateGetStorage
import io.nodle.substratesdk.rpc.SubstrateProvider
import io.nodle.substratesdk.scale.readAccountInfo
import io.nodle.substratesdk.scale.readAccountInfoLegacy
import io.nodle.substratesdk.scale.toU8a
import io.nodle.substratesdk.types.*
import io.nodle.substratesdk.utils.blake128
import io.nodle.substratesdk.utils.hexToBa
import io.nodle.substratesdk.utils.toHex
import io.nodle.substratesdk.utils.xxHash128
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.functions.Function5
import org.json.JSONObject
import java.math.BigInteger
import java.nio.ByteBuffer

/**
 * @author Lucien Loiseau on 14/07/20.
 */
@ExperimentalUnsignedTypes
fun Account.getAccountInfo(provider: SubstrateProvider): Single<AccountInfo> {
    return provider.getMetadata()
        .flatMap { metadata ->
            val ba = toU8a()
            val key = "System".xxHash128() + "Account".xxHash128() + ba.blake128() + ba
            provider.rpc.send<String>(StateGetStorage("0x" + key.toHex())).map {
                when (metadata.version) {
                    in 0..11 -> ByteBuffer.wrap(it.hexToBa()).readAccountInfoLegacy()
                    else -> ByteBuffer.wrap(it.hexToBa()).readAccountInfo()
                }
            }
        }
}

@ExperimentalUnsignedTypes
fun Account.getBalance(provider: SubstrateProvider): Single<BigInteger> {
    return getAccountInfo(provider)
        .map { it.data.free }
}

@ExperimentalUnsignedTypes
fun Wallet.signTx(
    provider: SubstrateProvider,
    destAccount: Account,
    amount: BigInteger,
    era: ExtrinsicEra = ImmortalEra()
): Single<Extrinsic> {
    return Single.zip(
        provider.transferCall(destAccount, amount),
        provider.getGenesisHash(),
        provider.getSpecVersion(),
        provider.getTransactionVersion(),
        getAccountInfo(provider),
        Function5 { call, genesisHash, specVersion, transactionVersion, sourceWalletInfo ->
            val payload = ExtrinsicPayload(
                genesisHash.hexToBa(),
                era,
                genesisHash.hexToBa(),
                call,
                sourceWalletInfo.nonce.toLong(),
                specVersion,
                BigInteger.ZERO,
                transactionVersion
            )
            val signature = privateKey.signMsg(payload.toU8a())
            val extrinsicSignature = ExtrinsicSignature(
                privateKey.generatePublicKey(),
                ExtrinsicEd25519Signature(signature),
                era,
                sourceWalletInfo.nonce.toLong(),
                BigInteger.ZERO
            )
            Extrinsic(
                extrinsicSignature,
                call
            )
        }
    )
}

fun Extrinsic.estimateFee(provider: SubstrateProvider): Single<BigInteger> {
    return provider.rpc
        .send<JSONObject>(PaymentQueryInfo("0x" + this.toU8a().toHex()))
        .map { it.getString("partialFee").toBigInteger() }
}

fun Extrinsic.send(provider: SubstrateProvider): Single<String> {
    return provider.rpc.send(AuthorSubmitExtrinsic("0x" + this.toU8a().toHex()))
}

@ExperimentalUnsignedTypes
fun Wallet.signAndSend(
    provider: SubstrateProvider,
    destAccount: Account,
    amount: BigInteger,
    era: ExtrinsicEra = ImmortalEra()
): Single<String> {
    return this
        .signTx(provider, destAccount, amount, era)
        .flatMap { it.send(provider) }
}
