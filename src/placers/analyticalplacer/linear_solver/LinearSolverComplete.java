package placers.analyticalplacer.linear_solver;

import placers.analyticalplacer.AnalyticalPlacer;


public class LinearSolverComplete extends LinearSolver {

    private DimensionSolverComplete solverX, solverY;

    public LinearSolverComplete(double[] coordinatesX, double[] coordinatesY, int numIOBlocks, double pseudoWeight, double epsilon) {
        super(coordinatesX, coordinatesY, numIOBlocks);

        this.solverX = new DimensionSolverComplete(coordinatesX, numIOBlocks, pseudoWeight, epsilon);
        this.solverY = new DimensionSolverComplete(coordinatesY, numIOBlocks, pseudoWeight, epsilon);
    }

    @Override
    public void addPseudoConnections(int[] legalX, int[] legalY) {
        int numIOBlocks = this.getNumIOBlocks();
        int numBlocks = this.coordinatesX.length;
        for(int blockIndex = numIOBlocks; blockIndex < numBlocks; blockIndex++) {
            this.solverX.addPseudoConnection(blockIndex, this.coordinatesX[blockIndex], legalX[blockIndex]);
            this.solverY.addPseudoConnection(blockIndex, this.coordinatesY[blockIndex], legalY[blockIndex]);
        }
    }

    @Override
    public void processNet(int[] blockIndexes) {

        int numNetBlocks = blockIndexes.length;
        double weightMultiplier = AnalyticalPlacer.getWeight(numNetBlocks) / (numNetBlocks - 1);

        // Nets with 2 blocks are common and can be processed very quick
        if(numNetBlocks == 2) {
            int blockIndex1 = blockIndexes[0], blockIndex2 = blockIndexes[1];
            boolean fixed1 = isFixed(blockIndex1), fixed2 = isFixed(blockIndex2);

            this.solverX.addConnectionMinMaxUnknown(
                    fixed1, blockIndex1, this.coordinatesX[blockIndex1],
                    fixed2, blockIndex2, this.coordinatesX[blockIndex2],
                    weightMultiplier);

            this.solverY.addConnectionMinMaxUnknown(
                    fixed1, blockIndex1, this.coordinatesY[blockIndex1],
                    fixed2, blockIndex2, this.coordinatesY[blockIndex2],
                    weightMultiplier);

            return;
        }


        // For bigger nets, we have to find the min and max block
        int initialBlockIndex = blockIndexes[0];
        double minX = this.coordinatesX[initialBlockIndex], maxX = this.coordinatesX[initialBlockIndex],
               minY = this.coordinatesY[initialBlockIndex], maxY = this.coordinatesY[initialBlockIndex];
        int minXIndex = initialBlockIndex, maxXIndex = initialBlockIndex,
            minYIndex = initialBlockIndex, maxYIndex = initialBlockIndex;

        for(int i = 1; i < numNetBlocks; i++) {
            int blockIndex = blockIndexes[i];
            double x = this.coordinatesX[blockIndex], y = this.coordinatesY[blockIndex];

            if(x < minX) {
                minX = x;
                minXIndex = blockIndex;
            } else if(x > maxX) {
                maxX = x;
                maxXIndex = blockIndex;
            }

            if(y < minY) {
                minY = y;
                minYIndex = blockIndex;
            } else if(y > maxY) {
                maxY = y;
                maxYIndex = blockIndex;
            }
        }


        boolean minXFixed = this.isFixed(minXIndex), maxXFixed = isFixed(maxXIndex),
                minYFixed = this.isFixed(minYIndex), maxYFixed = isFixed(maxYIndex);

        // Add connections from the min and max block to every block inside the net
        for(int i = 0; i < numNetBlocks; i++) {
            int blockIndex = blockIndexes[i];
            boolean isFixed = this.isFixed(blockIndex);
            double x = this.coordinatesX[blockIndex], y = this.coordinatesY[blockIndex];

            if(blockIndex != minXIndex) {
                this.solverX.addConnection(
                        minXFixed, minXIndex, minX,
                        isFixed, blockIndex, x,
                        weightMultiplier);

                if(blockIndex != maxXIndex) {
                    this.solverX.addConnection(
                            isFixed, blockIndex, x,
                            maxXFixed, maxXIndex, maxX,
                            weightMultiplier);
                }
            }

            if(blockIndex != minYIndex) {
                this.solverY.addConnection(
                        minYFixed, minYIndex, minY,
                        isFixed, blockIndex, y,
                        weightMultiplier);

                if(blockIndex != maxYIndex) {
                    this.solverY.addConnection(
                            isFixed, blockIndex, y,
                            maxYFixed, maxYIndex, maxY,
                            weightMultiplier);
                }
            }
        }
    }

    @Override
    public void solve() {
        this.solverX.solve();
        this.solverY.solve();
    }
}
