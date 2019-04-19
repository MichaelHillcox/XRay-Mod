package com.xray.client.render;

import com.xray.client.xray.XRayController;
import com.xray.common.Configuration;
import com.xray.common.XRay;
import com.xray.common.reference.block.BlockData;
import com.xray.common.reference.block.BlockInfo;
import com.xray.common.utils.WorldRegion;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.*;

public class ClientTick implements Runnable
{
	private final WorldRegion box;

	public ClientTick( WorldRegion region )
	{
		box = region;
	}

	@Override
	public void run() // Our thread code for finding ores near the player.
	{
		blockFinder();
	}

	/**
	 * Use XRayController.requestBlockFinder() to trigger a scan.
	 */
	private void blockFinder() {
        HashMap<String, BlockData> blocks = XRayController.getBlockStore().getStore();

		if ( blocks.isEmpty() ) {
		    if( !XrayRenderer.ores.isEmpty() )
		        XrayRenderer.ores.clear();
            return; // no need to scan the region if there's nothing to find
        }

		final World world = XRay.mc.world;
		final List<BlockInfo> renderQueue = new ArrayList<>();

		int lowBoundX, highBoundX, lowBoundY, highBoundY, lowBoundZ, highBoundZ;

		// Used for cleaning up the searching process
		IBlockState currentState;
		IBlockState defaultState;
		BlockData blockData;

		// Loop on chunks (x, z)
		for ( int chunkX = box.minChunkX; chunkX <= box.maxChunkX; chunkX++ )
		{
			// Pre-compute the extend bounds on X
			int x = chunkX << 4; // lowest x coord of the chunk in block/world coordinates
			lowBoundX = (x < box.minX) ? box.minX - x : 0; // lower bound for x within the extend
			highBoundX = (x + 15 > box.maxX) ? box.maxX - x : 15;// and higher bound. Basically, we clamp it to fit the radius.

			for ( int chunkZ = box.minChunkZ; chunkZ <= box.maxChunkZ; chunkZ++ )
			{
				// Time to getStore the chunk (16x256x16) and split it into 16 vertical extends (16x16x16)
				Chunk chunk = world.getChunkFromChunkCoords( chunkX, chunkZ );
				if ( chunk == null || !chunk.isLoaded() ) {
					continue; // We won't find anything interesting in unloaded chunks
				}
				ExtendedBlockStorage[] extendsList = chunk.getBlockStorageArray();

				// Pre-compute the extend bounds on Z
				int z = chunkZ << 4;
				lowBoundZ = (z < box.minZ) ? box.minZ - z : 0;
				highBoundZ = (z + 15 > box.maxZ) ? box.maxZ - z : 15;

				// Loop on the extends around the player's layer (6 down, 2 up)
				for ( int curExtend = box.minChunkY; curExtend <= box.maxChunkY; curExtend++ )
				{
					ExtendedBlockStorage ebs = extendsList[curExtend];
					if (ebs == null) // happens quite often!
						continue;

					// Pre-compute the extend bounds on Y
					int y = curExtend << 4;
					lowBoundY = (y < box.minY) ? box.minY - y : 0;
					highBoundY = (y + 15 > box.maxY) ? box.maxY - y : 15;

					// Now that we have an extend, let's check all its blocks
					for ( int i = lowBoundX; i <= highBoundX; i++ ) {
						for ( int j = lowBoundY; j <= highBoundY; j++ ) {
							for ( int k = lowBoundZ; k <= highBoundZ; k++ ) {
								currentState = ebs.get(i, j, k);

								// Reject blacklisted blocks
								if( XRayController.blackList.contains(currentState.getBlock()) )
									continue;

								defaultState = currentState.getBlock().getDefaultState();

								boolean defaultExists = blocks.containsKey(defaultState.toString());
								boolean currentExists = blocks.containsKey(currentState.toString());
								if( !defaultExists && !currentExists )
									continue;

								blockData = blocks.get(defaultExists ? defaultState.toString() : currentState.toString());
								if( blockData == null || !blockData.isDrawing() ) // fail safe
									continue;

								// Calculate distance from player to block. Fade out futher away blocks
								double alpha = !Configuration.shouldFade ? 255 : Math.max(0, ((XRayController.getRadius() - XRay.mc.player.getDistance(x + i, y + j, z + k)) / XRayController.getRadius() ) * 255);

								// Push the block to the render queue
								renderQueue.add(new BlockInfo(x + i, y + j, z + k, blockData.getOutline().getColor(), alpha));
							}
						}
					}
				}
			}
		}
		final BlockPos playerPos = XRay.mc.player.getPosition();
		renderQueue.sort((t, t1) -> Double.compare(t1.distanceSq(playerPos), t.distanceSq(playerPos)));
		XrayRenderer.ores.clear();
		XrayRenderer.ores.addAll( renderQueue ); // Add all our found blocks to the XrayRenderer.ores list. To be use by XrayRenderer when drawing.
	}

	/**
	 * Single-block version of blockFinder. Can safely be called directly
	 * for quick block check.
	 * @param pos the BlockPos to check
	 * @param state the current state of the block
	 * @param add true if the block was added to world, false if it was removed
	 */
	public static void checkBlock( BlockPos pos, IBlockState state, boolean add )
	{
		if ( !XRayController.drawOres() || XRayController.getBlockStore().getStore().isEmpty() )
		    return; // just pass

		String defaultState = state.getBlock().getDefaultState().toString();

		// Let's see if the block to check is an ore we monitor
		if ( XRayController.getBlockStore().getStore().containsKey(defaultState) ) // it's a block we are monitoring
		{
		    if( !add )
		    {
                XrayRenderer.ores.remove( new BlockInfo(pos, null, 0.0) );
                return;
            }

		    BlockData data = null;
            if( XRayController.getBlockStore().getStore().containsKey(defaultState) )
                data = XRayController.getBlockStore().getStore().get(defaultState);

            if( data == null )
            	return;

			double alpha = !Configuration.shouldFade ? 255 : Math.max(0, ((XRayController.getRadius() - XRay.mc.player.getDistance(pos.getX(), pos.getY(), pos.getZ())) / XRayController.getRadius() ) * 255);

            // the block was added to the world, let's add it to the drawing buffer
            XrayRenderer.ores.add( new BlockInfo(pos, data.getOutline().getColor(), alpha) );
		}
	}
}
