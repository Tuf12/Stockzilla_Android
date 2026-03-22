package com.example.stockzilla.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages the saved industry peer list per stock (stock_industry_peers).
 * Load returns peers enriched from separated raw+derived tables when available.
 */
class IndustryPeerRepository(
    private val peerDao: StockIndustryPeerDao,
    private val rawFactsDao: EdgarRawFactsDao
) {

    /**
     * Load saved peers for this owner, enriched with name/sector/industry/price/marketCap from separated tables.
     */
    suspend fun getSavedPeers(ownerSymbol: String): List<IndustryPeer> =
        withContext(Dispatchers.IO) {
            val entities = peerDao.getPeersForOwner(ownerSymbol)
            entities.map { entity ->
                val profile = rawFactsDao.getPeerProfileBySymbol(entity.peerSymbol)
                if (profile != null) {
                    IndustryPeer(
                        symbol = profile.symbol,
                        companyName = profile.companyName,
                        sector = profile.sector,
                        industry = profile.industry,
                        price = profile.price,
                        marketCap = profile.marketCap
                    )
                } else {
                    IndustryPeer(
                        symbol = entity.peerSymbol,
                        companyName = null,
                        sector = null,
                        industry = null,
                        price = null,
                        marketCap = null
                    )
                }
            }
        }

    /** Save a full list of peers (e.g. after seeding from getIndustryPeers). Replaces any existing for this owner. */
    suspend fun replacePeers(ownerSymbol: String, peers: List<IndustryPeer>, source: String = "initial") =
        withContext(Dispatchers.IO) {
            peerDao.removeAllForOwner(ownerSymbol)
            val now = System.currentTimeMillis()
            val entities = peers.map { peer ->
                StockIndustryPeerEntity(
                    ownerSymbol = ownerSymbol,
                    peerSymbol = peer.symbol,
                    addedAt = now,
                    source = source
                )
            }
            if (entities.isNotEmpty()) peerDao.insertAll(entities)
        }

    /** Add one peer (e.g. from "Add stock" flow). */
    suspend fun addPeer(ownerSymbol: String, peerSymbol: String, source: String = "user_added") =
        withContext(Dispatchers.IO) {
            peerDao.insert(
                StockIndustryPeerEntity(
                    ownerSymbol = ownerSymbol,
                    peerSymbol = peerSymbol,
                    addedAt = System.currentTimeMillis(),
                    source = source
                )
            )
        }

    /** Remove one peer from the list. */
    suspend fun removePeer(ownerSymbol: String, peerSymbol: String) = withContext(Dispatchers.IO) {
        peerDao.remove(ownerSymbol, peerSymbol)
    }

    suspend fun hasSavedPeers(ownerSymbol: String): Boolean = withContext(Dispatchers.IO) {
        peerDao.countForOwner(ownerSymbol) > 0
    }
}