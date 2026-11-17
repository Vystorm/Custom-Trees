package at.esoren.customtrees;
import Vystorm.vystorm_core._advancedMethods.structureGeneratorHandler.TreeSpecies;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static at.esoren.customtrees.GrowthSapling.*;

class SaplingBreedingTest {
    @Test void offspringInheritsBothTraitsIndependentlyFromSameSpecies(){
        var first=new Spec(TreeSpecies.CHERRY,CrownForm.FOREST,GrowthClass.GIANT);
        var second=new Spec(TreeSpecies.CHERRY,CrownForm.WINDSWEPT,GrowthClass.MEDIUM);
        Set<Spec> variants=new HashSet<>();
        for(int seed=0;seed<200;seed++) {
            Spec child=SaplingBreeding.inherit(first,second,new Random(seed*5349121L),0);variants.add(child);
            assertEquals(TreeSpecies.CHERRY,child.species());
            assertTrue(Set.of(first.crownForm(),second.crownForm()).contains(child.crownForm()));
            assertTrue(Set.of(first.growthClass(),second.growthClass()).contains(child.growthClass()));
        }
        assertEquals(4,variants.size());
    }
    @Test void mutationCannotEscapeExistingShapesOrChangeSpecies(){
        var parent=new Spec(TreeSpecies.CHERRY,CrownForm.ANCIENT,GrowthClass.COLOSSAL);
        for(int seed=0;seed<200;seed++) {
            Spec child=SaplingBreeding.inherit(parent,parent,new Random(seed*431312L),1);
            assertEquals(TreeSpecies.CHERRY,child.species());
            assertTrue(child.growthClass()==GrowthClass.COLOSSAL || child.growthClass()==GrowthClass.GIANT);
            assertNotNull(child.crownForm());
        }
    }
    @Test void mixedSpeciesRejectedBeforeAnyInventoryChange(){
        var first=new Spec(TreeSpecies.CHERRY,CrownForm.FOREST,GrowthClass.NORMAL);
        var second=new Spec(TreeSpecies.OAK,CrownForm.FOREST,GrowthClass.NORMAL);
        assertThrows(IllegalArgumentException.class,()->SaplingBreeding.inherit(first,second,new Random(1),.1));
    }
}
